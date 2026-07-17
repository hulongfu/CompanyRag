-- ================================================
-- 修复 set_tenant_id 函数：从 SET LOCAL 改为 SET
-- ================================================
-- 问题：TenantInterceptor 在事务外调用 setTenantContext，
--       SET LOCAL 只在事务内生效，导致 RLS 失败
-- 解决：使用 SET 在整个 session 生效
-- ================================================

-- 修改 set_tenant_id 函数，使用 SET 而不是 SET LOCAL
CREATE OR REPLACE FUNCTION set_tenant_id(p_tenant_id BIGINT) RETURNS VOID AS $$
BEGIN
    EXECUTE format('SET app.tenant_id = %L', p_tenant_id);
END;
$$ LANGUAGE plpgsql;

-- 验证函数已修改
-- SELECT prosrc FROM pg_proc WHERE proname = 'set_tenant_id';
