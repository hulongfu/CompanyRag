-- ================================================
-- RAG 文档入库（ETL）健壮性改造 - 数据库迁移 V4
-- 目的：落地 Spec §5.3 三处最小必要 DDL + 存量数据去重/回填 + 授权
-- 日期：2026-09-16
-- ================================================
--
-- 说明：
-- 1. 本脚本为【手动执行】（Flyway 已禁用），放入 sql/migrations/。
-- 2. 执行前【务必先备份】存量库（pg_dump），因为包含 doc_chunk 去重与 vector_store 回填。
-- 3. 三处改动作用于【每个租户 schema】（tenant_*），故使用 DO 循环动态 DDL，与 V1/V3 风格一致。
-- 4. RLS 会话变量读取统一复用现网函数 current_tenant_id()（见 sql/init.sql：
--    COALESCE(current_setting('app.tenant_id', true)::BIGINT, 0)），
--    与 V1/V3 及 TenantServiceImpl 的既有策略保持一致，不使用内联 current_setting。
-- ================================================

-- ========== 统一入口：遍历所有 tenant_% schema ==========
DO $$
DECLARE
    schema_record RECORD;
BEGIN
    FOR schema_record IN
        SELECT schema_name
        FROM information_schema.schemata
        WHERE schema_name LIKE 'tenant_%'
    LOOP
        -- ==================== 1. document_pipeline_state 表 ====================
        -- 独立任务状态表：承载分步状态机（PENDING/PARSING/CHUNKING/RAG_INGEST/VECTORIZING/SUCCESS/FAILED），
        -- 解决 rag_document.status(0/1/2/-1) 粒度过粗无法表达分段进度的问题。
        EXECUTE format('
            CREATE TABLE IF NOT EXISTS %1$I.document_pipeline_state (
                task_id      UUID PRIMARY KEY,
                document_id  BIGINT      NOT NULL,
                tenant_id    BIGINT      NOT NULL,
                step         VARCHAR(32) NOT NULL,
                status       VARCHAR(32) NOT NULL,
                error_step   VARCHAR(32),
                error_msg    TEXT,
                retry_count  INT         NOT NULL DEFAULT 0,
                create_time  TIMESTAMP   NOT NULL DEFAULT now(),
                update_time  TIMESTAMP   NOT NULL DEFAULT now()
            )', schema_record.schema_name);

        -- RLS 深度防御（辅助隔离），与 rag_document 同模式
        EXECUTE format('ALTER TABLE %1$I.document_pipeline_state ENABLE ROW LEVEL SECURITY', schema_record.schema_name);
        EXECUTE format('ALTER TABLE %1$I.document_pipeline_state FORCE ROW LEVEL SECURITY', schema_record.schema_name);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation_pipeline ON %1$I.document_pipeline_state', schema_record.schema_name);
        EXECUTE format('
            CREATE POLICY tenant_isolation_pipeline ON %1$I.document_pipeline_state
                FOR ALL
                TO company_rag_app
                USING (tenant_id = current_tenant_id())
                WITH CHECK (tenant_id = current_tenant_id())', schema_record.schema_name);

        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_pipeline_tenant ON %1$I.document_pipeline_state (tenant_id)',
                       schema_record.schema_name);
        EXECUTE format('CREATE INDEX IF NOT EXISTS idx_pipeline_status ON %1$I.document_pipeline_state (status)',
                       schema_record.schema_name);

        -- ==================== 2. doc_chunk 唯一约束（幂等） ====================
        -- 先按 (document_id, chunk_index) 去重，重复保留最小 id，再建唯一约束。
        -- 唯一约束保证分步幂等补跑不产生重复 chunk。
        EXECUTE format('
            DELETE FROM %1$I.doc_chunk a USING %1$I.doc_chunk b
             WHERE a.document_id = b.document_id
               AND a.chunk_index = b.chunk_index
               AND a.id > b.id', schema_record.schema_name);

        EXECUTE format('
            ALTER TABLE %1$I.doc_chunk DROP CONSTRAINT IF EXISTS uq_doc_chunk_doc_idx', schema_record.schema_name);
        EXECUTE format('
            ALTER TABLE %1$I.doc_chunk
                ADD CONSTRAINT uq_doc_chunk_doc_idx UNIQUE (document_id, chunk_index)', schema_record.schema_name);

        -- ==================== 3. vector_store 加 chunk_id + 唯一索引（幂等） ====================
        -- 历史实现用 UUID.randomUUID() 作向量 id（Spring AI 要求 UUID），
        -- 无法稳定映射到 doc_chunk，导致向量化重试产生重复向量。
        -- 新增 chunk_id 列（冗余存 doc_chunk.id），配合部分唯一索引实现向量化幂等。
        EXECUTE format('
            ALTER TABLE %1$I.vector_store ADD COLUMN IF NOT EXISTS chunk_id BIGINT', schema_record.schema_name);

        -- 存量回填：从 metadata->>''chunkId'' 提取（存的是 doc_chunk.id），
        -- 重复 chunk_id 保留一行（uuid 无可比 min()，用 DISTINCT ON 每组取一行），
        -- 其余行保持 chunk_id 为空以避免冲突唯一索引。
        EXECUTE format('
            UPDATE %1$I.vector_store v SET chunk_id = c.chunk_id
            FROM (
                SELECT DISTINCT ON ((metadata->>''chunkId'')::bigint)
                       (metadata->>''chunkId'')::bigint AS chunk_id, id
                FROM %1$I.vector_store
                WHERE metadata ? ''chunkId''
                  AND (metadata->>''chunkId'') ~ ''^[0-9]+$''
                ORDER BY (metadata->>''chunkId'')::bigint, id
            ) c
            WHERE v.id = c.id', schema_record.schema_name);

        EXECUTE format('
            CREATE UNIQUE INDEX IF NOT EXISTS uq_vector_store_chunk
                ON %1$I.vector_store (chunk_id) WHERE chunk_id IS NOT NULL', schema_record.schema_name);

        -- ==================== 4. 授权 company_rag_app ====================
        -- 新表及新列需显式授权（V1 的循环授权只覆盖当时已存在的表，默认权限对新表同样生效，此处显式补一次）
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %1$I.document_pipeline_state TO company_rag_app',
                       schema_record.schema_name);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %1$I.doc_chunk TO company_rag_app',
                       schema_record.schema_name);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %1$I.vector_store TO company_rag_app',
                       schema_record.schema_name);

        RAISE NOTICE 'V4: schema % 完成 document_pipeline_state + doc_chunk 唯一约束 + vector_store.chunk_id',
                     schema_record.schema_name;
    END LOOP;
END $$;

-- ================================================
-- 验证脚本（手动执行）
-- ================================================
-- 1. 确认每个租户 schema 的新表存在
--    SELECT schemaname, tablename FROM pg_tables WHERE tablename = 'document_pipeline_state' ORDER BY 1;
--
-- 2. 确认 RLS 策略
--    SELECT schemaname, tablename, policyname FROM pg_policies
--    WHERE policyname = 'tenant_isolation_pipeline' ORDER BY 1;
--
-- 3. 确认 doc_chunk 唯一约束
--    SELECT schemaname, conname FROM pg_constraint c
--    JOIN pg_class t ON t.oid = c.conrelid
--    JOIN pg_namespace n ON n.oid = t.relnamespace
--    WHERE c.conname = 'uq_doc_chunk_doc_idx' ORDER BY 1;
--
-- 4. 确认 vector_store chunk_id 唯一索引
--    SELECT schemaname, indexname FROM pg_indexes
--    WHERE indexname = 'uq_vector_store_chunk' ORDER BY 1;
--
-- 5. 确认回填结果（chunk_id IS NOT NULL 的向量数应等于 doc_chunk 有对应 chunkId 的数量）
--    SELECT count(*) FROM tenant_xxx.vector_store WHERE chunk_id IS NOT NULL;
--
-- 注意：执行前先 pg_dump 备份；脚本内已对重复执行做幂等（IF NOT EXISTS / DROP IF EXISTS）。
-- ================================================