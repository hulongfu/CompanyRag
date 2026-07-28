# 租户全文检索自动初始化功能

## 功能说明

在创建新租户时，系统会自动为该租户的 Schema 初始化全文检索支持，包括：

1. **content_tsv 列**：tsvector 类型，用于存储全文检索向量
2. **GIN 索引**：加速全文检索查询
3. **触发器**：自动更新 content_tsv 列（INSERT/UPDATE 时）
4. **pg_trgm 扩展**：支持模糊匹配
5. **trgm 索引**：加速模糊匹配查询

## 实现位置

**文件**：`company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`

**方法**：`createTenantSchema(Tenant tenant)` 第 114-141 行

## 初始化 SQL

```sql
-- 添加 tsvector 列用于全文检索
ALTER TABLE {schema}.vector_store ADD COLUMN IF NOT EXISTS content_tsv tsvector;

-- 创建 GIN 索引加速全文检索
CREATE INDEX IF NOT EXISTS idx_{schema}_vector_store_content_tsv 
    ON {schema}.vector_store USING GIN (content_tsv);

-- 创建触发器自动更新 tsvector
DROP TRIGGER IF EXISTS tsvectorupdate_{schema} ON {schema}.vector_store;
CREATE TRIGGER tsvectorupdate_{schema} 
    BEFORE INSERT OR UPDATE ON {schema}.vector_store
    FOR EACH ROW EXECUTE FUNCTION
    tsvector_update_trigger(content_tsv, 'pg_catalog.simple', content);

-- 初始化现有数据的 tsvector
UPDATE {schema}.vector_store SET content_tsv = to_tsvector('pg_catalog.simple', content);

-- 启用 pg_trgm 扩展（用于模糊匹配）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 创建 trgm 索引加速模糊匹配
CREATE INDEX IF NOT EXISTS idx_{schema}_vector_store_content_trgm 
    ON {schema}.vector_store USING GIN (content gin_trgm_ops);
```

## 测试步骤

### 方法 1：通过 API 创建新租户

```bash
# 调用创建租户 API
curl -X POST http://localhost:8080/api/tenants \
  -H "Content-Type: application/json" \
  -d '{
    "tenantCode": "test_fulltext",
    "tenantName": "全文检索测试租户",
    "status": 1
  }'
```

### 方法 2：数据库验证

创建租户后，在 PostgreSQL 中执行以下 SQL 验证：

```sql
-- 1. 验证 content_tsv 列存在
SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_schema = 'tenant_test_fulltext' 
  AND table_name = 'vector_store' 
  AND column_name = 'content_tsv';

-- 2. 验证全文检索 GIN 索引存在
SELECT indexname, indexdef 
FROM pg_indexes 
WHERE schemaname = 'tenant_test_fulltext' 
  AND tablename = 'vector_store' 
  AND indexname LIKE '%content_tsv%';

-- 3. 验证触发器存在
SELECT trigger_name, event_manipulation, action_statement 
FROM information_schema.triggers 
WHERE trigger_schema = 'tenant_test_fulltext' 
  AND trigger_name LIKE '%tsvector%'
  AND event_object_table = 'vector_store';

-- 4. 验证 trgm 索引存在
SELECT indexname, indexdef 
FROM pg_indexes 
WHERE schemaname = 'tenant_test_fulltext' 
  AND tablename = 'vector_store' 
  AND indexname LIKE '%content_trgm%';

-- 5. 测试触发器功能
INSERT INTO tenant_test_fulltext.vector_store (id, content, embedding) 
VALUES (gen_random_uuid(), '测试全文检索内容', NULL);

SELECT content, content_tsv 
FROM tenant_test_fulltext.vector_store 
WHERE content = '测试全文检索内容';
-- 应该看到 content_tsv 已自动填充
```

## 日志输出

创建租户时，日志会显示：

```
INFO  为租户 [test_fulltext] 初始化全文检索支持：content_tsv 列、GIN 索引、触发器、trgm 索引
INFO  为租户 [test_fulltext] 创建独立 Schema 完成：tenant_test_fulltext | 已创建业务表和 RLS 策略
```

## 与迁移脚本的关系

项目中的 `sql/hybrid-search-schema-migration.sql` 脚本用于为**现有租户**手动添加全文检索支持。

本功能实现后，**新建租户**会自动执行类似的 SQL，无需手动运行迁移脚本。

## 注意事项

1. **pg_trgm 扩展**：需要在数据库中启用 `pg_trgm` 扩展（PostgreSQL 内置扩展）
2. **权限要求**：创建扩展需要 superuser 或 CREATE 权限
3. **性能影响**：
   - GIN 索引会略微增加 INSERT/UPDATE 时间
   - 但大幅提升全文检索和模糊匹配查询性能
4. **存储空间**：content_tsv 列会额外占用存储空间（约为原文的 20-30%）

## 相关文件

- 实现代码：`company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`
- 迁移脚本：`sql/hybrid-search-schema-migration.sql`
- 全文检索器：`company-rag-rag/src/main/java/com/company/rag/rag/retriever/impl/FullTextRetriever.java`
