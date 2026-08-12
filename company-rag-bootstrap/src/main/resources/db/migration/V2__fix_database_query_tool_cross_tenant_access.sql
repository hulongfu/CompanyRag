-- ================================================
-- DatabaseQueryTool 跨租户访问漏洞修复
-- 日期：2026-08-11
-- ================================================
-- 
-- 问题描述：
-- DatabaseQueryTool 使用全局 JdbcTemplate 执行 LLM 生成的 SQL，
-- 没有租户隔离，用户可通过 SELECT * FROM tenant_other.vector_store
-- 访问其他租户数据。
-- 
-- 修复方案：
-- 1. 应用层：DatabaseQueryTool 强制检查租户上下文，自动添加 schema 前缀，禁止显式指定 schema
-- 2. 数据库层：收回 company_rag_app 对其他租户 schema 的访问权限
-- ================================================

-- ========== 1. 收回所有租户 schema 的公共访问权限 ==========
-- 注意：这会影响所有现有租户，需要在应用层修复后执行

DO $$
DECLARE
    schema_record RECORD;
BEGIN
    -- 遍历所有 tenant_ 开头的 schema
    FOR schema_record IN 
        SELECT schema_name 
        FROM information_schema.schemata 
        WHERE schema_name LIKE 'tenant_%'
    LOOP
        -- 收回所有表的权限
        EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM company_rag_app', schema_record.schema_name);
        
        -- 收回所有序列的权限
        EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM company_rag_app', schema_record.schema_name);
        
        -- 收回 schema 使用权限
        EXECUTE format('REVOKE USAGE ON SCHEMA %I FROM company_rag_app', schema_record.schema_name);
        
        RAISE NOTICE '已收回 company_rag_app 对 schema % 的所有权限', schema_record.schema_name;
    END LOOP;
END $$;

-- ========== 2. 重新授予受限权限（仅允许通过 search_path 访问当前租户） ==========
-- 注意：权限将通过应用层的 TenantSchemaInterceptor 在连接级别动态设置
-- 这里只授予最基本的连接权限

DO $$
DECLARE
    schema_record RECORD;
BEGIN
    FOR schema_record IN 
        SELECT schema_name 
        FROM information_schema.schemata 
        WHERE schema_name LIKE 'tenant_%'
    LOOP
        -- 只授予 USAGE 权限（允许在 search_path 中使用）
        -- 具体的表访问权限由 RLS 策略控制
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO company_rag_app', schema_record.schema_name);
        
        -- 不直接授予表权限，依赖 RLS 策略控制访问
        -- rag_document、doc_chunk、rag_session、rag_session_meta 已有 RLS 策略
        -- vector_store 通过 Schema 隔离（不授予跨 schema 访问）
        
        RAISE NOTICE '已为 schema % 授予受限权限', schema_record.schema_name;
    END LOOP;
END $$;

-- ========== 3. 验证脚本 ==========
-- 检查 company_rag_app 的权限

SELECT 
    grantee,
    table_schema,
    table_name,
    privilege_type
FROM information_schema.role_table_grants
WHERE grantee = 'company_rag_app'
  AND table_schema LIKE 'tenant_%'
ORDER BY table_schema, table_name;

-- 检查 schema 权限
SELECT 
    grantee,
    table_schema,
    privilege_type
FROM information_schema.role_usage_grants
WHERE grantee = 'company_rag_app'
  AND object_schema LIKE 'tenant_%';

-- ================================================
-- 修复完成！
-- 
-- 应用层修复：
-- - DatabaseQueryTool 现在强制检查租户上下文
-- - 自动添加当前租户 schema 前缀
-- - 禁止显式指定其他 schema
-- 
-- 数据库层修复：
-- - 收回 company_rag_app 对所有租户 schema 的直接访问权
-- - 仅保留 USAGE 权限，依赖 search_path 路由
-- - RLS 策略继续保护 rag_document、doc_chunk、rag_session、rag_session_meta
-- ================================================
