-- =====================================================
-- 系统初始化脚本：创建唯一的平台超级管理员账号 + 默认租户
-- =====================================================
-- 说明：
-- 1. 此脚本仅在首次部署时执行一次
-- 2. admin 账号是平台级超级管理员，关联所有租户
-- 3. 默认租户 tenant_default 用于 admin 首次登录
-- 4. 密码通过 BCrypt 加密，默认密码：admin123
-- 5. 执行后请妥善保管密码，建议首次登录后修改
-- =====================================================

-- ========== 1. 创建 admin 账号 ==========
INSERT INTO sys_user (username, password, display_name, email, role, status, create_time, update_time)
SELECT 'admin', 
       '$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6', 
       '系统管理员', 
       'admin@company.com', 
       'admin', 
       1, 
       NOW(), 
       NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM sys_user WHERE username = 'admin'
);

-- ========== 2. 创建默认租户 ==========
INSERT INTO sys_tenant (tenant_code, tenant_name, schema_name, status, create_time, update_time)
SELECT 'tenant_default', 
       '默认租户', 
       'tenant_tenant_default', 
       1, 
       NOW(), 
       NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM sys_tenant WHERE tenant_code = 'tenant_default'
);

-- ========== 3. 关联 admin 与默认租户 ==========
-- 获取 admin 用户 ID 和默认租户 ID，建立关联
INSERT INTO sys_user_tenant_rel (user_id, tenant_id)
SELECT u.id, t.id
FROM sys_user u, sys_tenant t
WHERE u.username = 'admin' 
  AND t.tenant_code = 'tenant_default'
  AND NOT EXISTS (
      SELECT 1 FROM sys_user_tenant_rel rel 
      WHERE rel.user_id = u.id AND rel.tenant_id = t.id
  );

-- ========== 4. 创建默认租户的 schema 和业务表 ==========
-- 注意：这部分需要在 Java 代码中执行，因为涉及复杂的 SQL 和 RLS 策略
-- 这里提供一个简化的版本，仅创建 schema 和基本表结构
-- 完整的表结构请通过 Java 代码的 createTenantSchema 方法创建

-- 4.1 创建 schema
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'tenant_tenant_default') THEN
        CREATE SCHEMA tenant_tenant_default;
        RAISE NOTICE '已创建 schema: tenant_tenant_default';
    ELSE
        RAISE NOTICE 'schema tenant_tenant_default 已存在';
    END IF;
END $$;

-- 4.2 创建业务表（简化版，详细结构见 TenantServiceImpl.createTenantSchema）
DO $$
BEGIN
    -- rag_document 表
    CREATE TABLE IF NOT EXISTS tenant_tenant_default.rag_document (
        id BIGSERIAL PRIMARY KEY,
        tenant_id BIGINT NOT NULL,
        file_name VARCHAR(256) NOT NULL,
        file_type VARCHAR(32),
        file_size BIGINT,
        file_path VARCHAR(512),
        title VARCHAR(256),
        chunk_count INTEGER DEFAULT 0,
        status INTEGER DEFAULT 0,
        error_msg TEXT,
        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
    
    -- doc_chunk 表
    CREATE TABLE IF NOT EXISTS tenant_tenant_default.doc_chunk (
        id BIGSERIAL PRIMARY KEY,
        document_id BIGINT NOT NULL REFERENCES tenant_tenant_default.rag_document(id) ON DELETE CASCADE,
        tenant_id BIGINT NOT NULL,
        chunk_index INTEGER NOT NULL,
        content TEXT NOT NULL,
        token_count INTEGER DEFAULT 0,
        split_strategy VARCHAR(32),
        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
    
    -- vector_store 表（需要 pgvector 扩展）
    CREATE EXTENSION IF NOT EXISTS vector;
    CREATE TABLE IF NOT EXISTS tenant_tenant_default.vector_store (
        id UUID PRIMARY KEY,
        content TEXT,
        metadata JSONB,
        embedding vector(1024)
    );
    
    -- rag_session 表
    CREATE TABLE IF NOT EXISTS tenant_tenant_default.rag_session (
        id BIGSERIAL PRIMARY KEY,
        session_id VARCHAR(128) NOT NULL,
        tenant_id BIGINT NOT NULL,
        user_id BIGINT,
        query TEXT NOT NULL,
        answer TEXT,
        context TEXT,
        tokens_input INTEGER DEFAULT 0,
        tokens_output INTEGER DEFAULT 0,
        latency_ms INTEGER DEFAULT 0,
        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
    
    -- rag_session_meta 表
    CREATE TABLE IF NOT EXISTS tenant_tenant_default.rag_session_meta (
        id BIGSERIAL PRIMARY KEY,
        session_id VARCHAR(128) NOT NULL,
        tenant_id BIGINT NOT NULL,
        user_id BIGINT,
        title VARCHAR(256),
        last_query TEXT,
        message_count INTEGER DEFAULT 0,
        is_deleted BOOLEAN DEFAULT FALSE,
        tags JSONB,
        metadata JSONB,
        create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    );
    
    RAISE NOTICE '已创建默认租户的业务表';
END $$;

-- 4.3 创建索引
DO $$
BEGIN
    -- rag_document 索引
    CREATE INDEX IF NOT EXISTS idx_tenant_default_doc_tenant 
        ON tenant_tenant_default.rag_document(tenant_id);
    
    -- doc_chunk 索引
    CREATE INDEX IF NOT EXISTS idx_tenant_default_chunk_document 
        ON tenant_tenant_default.doc_chunk(document_id);
    CREATE INDEX IF NOT EXISTS idx_tenant_default_chunk_content_trgm 
        ON tenant_tenant_default.doc_chunk USING gin (content gin_trgm_ops);
    
    -- rag_document 全文索引
    CREATE INDEX IF NOT EXISTS idx_tenant_default_document_title_trgm 
        ON tenant_tenant_default.rag_document USING gin (title gin_trgm_ops);
    
    -- rag_session 索引
    CREATE INDEX IF NOT EXISTS idx_tenant_default_session_tenant 
        ON tenant_tenant_default.rag_session(tenant_id, session_id);
    
    -- vector_store 向量索引（需要 pgvector）
    CREATE INDEX IF NOT EXISTS idx_tenant_default_vector_store_embedding 
        ON tenant_tenant_default.vector_store
        USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);
    
    RAISE NOTICE '已创建默认租户的索引';
END $$;

-- 4.4 启用 RLS（行级安全）
DO $$
BEGIN
    -- 启用 RLS
    ALTER TABLE tenant_tenant_default.rag_document ENABLE ROW LEVEL SECURITY;
    ALTER TABLE tenant_tenant_default.rag_document FORCE ROW LEVEL SECURITY;
    ALTER TABLE tenant_tenant_default.doc_chunk ENABLE ROW LEVEL SECURITY;
    ALTER TABLE tenant_tenant_default.doc_chunk FORCE ROW LEVEL SECURITY;
    ALTER TABLE tenant_tenant_default.rag_session ENABLE ROW LEVEL SECURITY;
    ALTER TABLE tenant_tenant_default.rag_session FORCE ROW LEVEL SECURITY;
    ALTER TABLE tenant_tenant_default.rag_session_meta ENABLE ROW LEVEL SECURITY;
    ALTER TABLE tenant_tenant_default.rag_session_meta FORCE ROW LEVEL SECURITY;
    
    -- 删除旧策略（如果存在）
    DROP POLICY IF EXISTS tenant_isolation_document ON tenant_tenant_default.rag_document;
    DROP POLICY IF EXISTS tenant_isolation_chunk ON tenant_tenant_default.doc_chunk;
    DROP POLICY IF EXISTS tenant_isolation_session ON tenant_tenant_default.rag_session;
    DROP POLICY IF EXISTS tenant_isolation_session_meta ON tenant_tenant_default.rag_session_meta;
    
    -- 创建租户隔离策略
    CREATE POLICY tenant_isolation_document ON tenant_tenant_default.rag_document
        FOR ALL TO company_rag_app
        USING (tenant_id = current_tenant_id())
        WITH CHECK (tenant_id = current_tenant_id());
    
    CREATE POLICY tenant_isolation_chunk ON tenant_tenant_default.doc_chunk
        FOR ALL TO company_rag_app
        USING (tenant_id = current_tenant_id())
        WITH CHECK (tenant_id = current_tenant_id());
    
    CREATE POLICY tenant_isolation_session ON tenant_tenant_default.rag_session
        FOR ALL TO company_rag_app
        USING (tenant_id = current_tenant_id())
        WITH CHECK (tenant_id = current_tenant_id());
    
    CREATE POLICY tenant_isolation_session_meta ON tenant_tenant_default.rag_session_meta
        FOR ALL TO company_rag_app
        USING (tenant_id = current_tenant_id())
        WITH CHECK (tenant_id = current_tenant_id());
    
    RAISE NOTICE '已启用默认租户的 RLS 策略';
END $$;

-- 4.5 授予权限
DO $$
BEGIN
    GRANT USAGE ON SCHEMA tenant_tenant_default TO company_rag_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA tenant_tenant_default TO company_rag_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA tenant_tenant_default TO company_rag_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA tenant_tenant_default 
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO company_rag_app;
    ALTER DEFAULT PRIVILEGES IN SCHEMA tenant_tenant_default 
        GRANT USAGE, SELECT ON SEQUENCES TO company_rag_app;
    
    RAISE NOTICE '已授予 company_rag_app 对默认租户 schema 的权限';
END $$;

-- ========== 输出提示信息 ==========
DO $$
DECLARE
    admin_id BIGINT;
    tenant_id BIGINT;
BEGIN
    SELECT id INTO admin_id FROM sys_user WHERE username = 'admin';
    SELECT id INTO tenant_id FROM sys_tenant WHERE tenant_code = 'tenant_default';
    
    IF admin_id IS NOT NULL AND tenant_id IS NOT NULL THEN
        RAISE NOTICE '========================================';
        RAISE NOTICE '系统初始化完成！';
        RAISE NOTICE '----------------------------------------';
        RAISE NOTICE 'admin 账号已创建：';
        RAISE NOTICE '  用户名：admin';
        RAISE NOTICE '  密码：admin123';
        RAISE NOTICE '  请首次登录后立即修改密码！';
        RAISE NOTICE '----------------------------------------';
        RAISE NOTICE '默认租户已创建：';
        RAISE NOTICE '  租户编码：tenant_default';
        RAISE NOTICE '  租户名称：默认租户';
        RAISE NOTICE '  Schema: tenant_tenant_default';
        RAISE NOTICE '----------------------------------------';
        RAISE NOTICE 'admin 已关联默认租户：';
        RAISE NOTICE '  userId: ' || admin_id;
        RAISE NOTICE '  tenantId: ' || tenant_id;
        RAISE NOTICE '========================================';
    ELSE
        RAISE EXCEPTION '系统初始化失败';
    END IF;
END $$;

-- =====================================================
-- 使用说明：
-- 1. 首次部署时执行此脚本
-- 2. 使用 admin/admin123 登录系统
-- 3. 默认租户 tenant_default 已创建并关联 admin
-- 4. 通过租户管理界面创建其他租户（会自动关联创建者）
-- 5. 通过用户管理界面创建其他普通用户（user/viewer 角色）
-- 6. 建议立即修改 admin 密码
-- =====================================================
