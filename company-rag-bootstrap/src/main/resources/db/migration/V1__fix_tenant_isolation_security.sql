-- ================================================
-- 多租户隔离安全修复脚本
-- 修复问题：RLS 策略被 postgres 超级用户绕过
-- 日期：2026-08-09
-- ================================================
-- 
-- 重要说明：
-- 1. 本脚本修复 RLS 策略（移除 postgres 后门，增加 WITH CHECK + FORCE RLS）
-- 2. vector_store 表仅依赖 Schema 隔离，不使用 RLS（PgVectorStore 不经过 MyBatis）
-- 3. 清理 vector_store 表可能存在的旧 tenant_id 列和 RLS 策略（如果之前错误添加过）
-- ================================================

-- ========== 1. 创建专用数据库用户（非超级用户） ==========
DO $$
BEGIN
    -- 如果用户不存在则创建
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'company_rag_app') THEN
        CREATE USER company_rag_app WITH PASSWORD 'company_rag_app_password_change_me';
        RAISE NOTICE '已创建专用用户：company_rag_app';
    ELSE
        RAISE NOTICE '用户 company_rag_app 已存在，跳过创建';
    END IF;
    
    -- 授予数据库连接权限
    GRANT CONNECT ON DATABASE company_rag TO company_rag_app;
    RAISE NOTICE '已授予 company_rag_app 数据库连接权限';
END $$;

-- ========== 2. 授予 public schema 系统表权限 ==========
-- sys_tenant 和 sys_user 表在 public schema，需要授予查询权限
GRANT USAGE ON SCHEMA public TO company_rag_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.sys_tenant TO company_rag_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.sys_user TO company_rag_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.sys_user_tenant_rel TO company_rag_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO company_rag_app;

-- ========== 3. 为现有租户 schema 授权 ==========
-- 注意：此部分需要在实际环境中根据存在的租户 schema 执行
-- 以下为示例，假设存在 tenant_default schema

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
        -- 授予 schema 使用权限
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO company_rag_app', schema_record.schema_name);
        
        -- 授予所有表的 CRUD 权限
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO company_rag_app', schema_record.schema_name);
        
        -- 授予所有序列的 USAGE 和 SELECT 权限
        EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO company_rag_app', schema_record.schema_name);
        
        RAISE NOTICE '已授予 company_rag_app 对 schema % 的权限', schema_record.schema_name;
    END LOOP;
END $$;

-- ========== 4. 设置默认权限（未来创建的表自动授权） ==========
-- 注意：默认权限只对设置后创建的表生效
-- 需要在每个租户 schema 中分别设置

DO $$
DECLARE
    schema_record RECORD;
BEGIN
    FOR schema_record IN 
        SELECT schema_name 
        FROM information_schema.schemata 
        WHERE schema_name LIKE 'tenant_%'
    LOOP
        -- 设置默认权限：未来创建的表自动授予 company_rag_app 权限
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO company_rag_app', schema_record.schema_name);
        EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO company_rag_app', schema_record.schema_name);
        
        RAISE NOTICE '已为 schema % 设置默认权限', schema_record.schema_name;
    END LOOP;
END $$;

-- ========== 5. 修复 RLS 策略（移除 postgres 后门） ==========
-- 注意：RLS 策略需要在每个租户 schema 中分别修复

DO $$
DECLARE
    schema_record RECORD;
BEGIN
    FOR schema_record IN 
        SELECT schema_name 
        FROM information_schema.schemata 
        WHERE schema_name LIKE 'tenant_%'
    LOOP
        -- 修复 rag_document 表的 RLS 策略（增加 WITH CHECK + FORCE RLS）
        EXECUTE format('
            ALTER TABLE %1$I.rag_document ENABLE ROW LEVEL SECURITY;
            ALTER TABLE %1$I.rag_document FORCE ROW LEVEL SECURITY;
            DROP POLICY IF EXISTS tenant_isolation_document ON %1$I.rag_document;
            CREATE POLICY tenant_isolation_document ON %1$I.rag_document
                FOR ALL
                TO company_rag_app
                USING (tenant_id = current_tenant_id())
                WITH CHECK (tenant_id = current_tenant_id());
        ', schema_record.schema_name);
        
        -- 修复 doc_chunk 表的 RLS 策略（增加 WITH CHECK + FORCE RLS）
        EXECUTE format('
            ALTER TABLE %1$I.doc_chunk ENABLE ROW LEVEL SECURITY;
            ALTER TABLE %1$I.doc_chunk FORCE ROW LEVEL SECURITY;
            DROP POLICY IF EXISTS tenant_isolation_chunk ON %1$I.doc_chunk;
            CREATE POLICY tenant_isolation_chunk ON %1$I.doc_chunk
                FOR ALL
                TO company_rag_app
                USING (tenant_id = current_tenant_id())
                WITH CHECK (tenant_id = current_tenant_id());
        ', schema_record.schema_name);
        
        -- 注意：vector_store 表仅依赖 Schema 隔离，不使用 RLS
        -- 原因：PgVectorStore 通过 TenantAwareJdbcTemplate 直连 JDBC，
        -- 不经过 MyBatis 拦截器设置 app.tenant_id，
        -- 强加 RLS 会导致 current_tenant_id()=0，所有向量 tenant_id=0，
        -- 造成跨租户数据泄露 + 旧数据不可见
        
        -- 清理 vector_store 表的旧 RLS 策略和 tenant_id 列（如果存在）
        -- 这是为了修复之前错误添加的 RLS 回归
        EXECUTE format('
            DROP POLICY IF EXISTS tenant_isolation_vector ON %1$I.vector_store;
            ALTER TABLE %1$I.vector_store DROP COLUMN IF EXISTS tenant_id;
        ', schema_record.schema_name);
        
        -- 修复 rag_session 表的 RLS 策略（增加 WITH CHECK + FORCE RLS）
        EXECUTE format('
            ALTER TABLE %1$I.rag_session ENABLE ROW LEVEL SECURITY;
            ALTER TABLE %1$I.rag_session FORCE ROW LEVEL SECURITY;
            DROP POLICY IF EXISTS tenant_isolation_session ON %1$I.rag_session;
            CREATE POLICY tenant_isolation_session ON %1$I.rag_session
                FOR ALL
                TO company_rag_app
                USING (tenant_id = current_tenant_id())
                WITH CHECK (tenant_id = current_tenant_id());
        ', schema_record.schema_name);
        
        -- 修复 rag_session_meta 表的 RLS 策略（增加 WITH CHECK + FORCE RLS）
        EXECUTE format('
            ALTER TABLE %1$I.rag_session_meta ENABLE ROW LEVEL SECURITY;
            ALTER TABLE %1$I.rag_session_meta FORCE ROW LEVEL SECURITY;
            DROP POLICY IF EXISTS tenant_isolation_session_meta ON %1$I.rag_session_meta;
            CREATE POLICY tenant_isolation_session_meta ON %1$I.rag_session_meta
                FOR ALL
                TO company_rag_app
                USING (tenant_id = current_tenant_id())
                WITH CHECK (tenant_id = current_tenant_id());
        ', schema_record.schema_name);
        
        RAISE NOTICE '已修复 schema % 的 RLS 策略', schema_record.schema_name;
    END LOOP;
END $$;

-- ========== 6. 验证脚本 ==========
-- 以下查询用于验证修复是否成功
-- 注意：如果通过 DBeaver 执行，请手动运行以下 SELECT 语句

-- 检查用户是否存在
SELECT rolname, rolsuper, rolcreaterole, rolcreatedb 
FROM pg_catalog.pg_roles 
WHERE rolname = 'company_rag_app';

-- 检查数据库权限
SELECT grantee, table_name, privilege_type 
FROM information_schema.role_table_grants 
WHERE grantee = 'company_rag_app' 
  AND table_schema = 'public'
LIMIT 10;

-- 检查 RLS 策略定义（应该只包含 current_tenant_id() 条件）
SELECT 
    schemaname,
    tablename,
    policyname,
    qual
FROM pg_policies
WHERE policyname LIKE 'tenant_isolation_%'
ORDER BY schemaname, tablename;

-- ================================================
-- 修复完成！请务必修改公司密码：company_rag_app_password_change_me
-- ================================================
