-- 用户 - 租户关联表 DDL
-- 执行数据库：company_rag (public schema)

-- 1. 修改 sys_user 表（移除 tenant_id 字段）
ALTER TABLE sys_user DROP COLUMN IF EXISTS tenant_id;

-- 2. 创建用户 - 租户关联表
CREATE TABLE sys_user_tenant_rel (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    UNIQUE (user_id, tenant_id),
    CONSTRAINT fk_user_tenant_rel_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_user_tenant_rel_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant(id)
);

-- 3. 创建索引（优化查询）
CREATE INDEX idx_user_tenant_rel_user_id ON sys_user_tenant_rel(user_id);
CREATE INDEX idx_user_tenant_rel_tenant_id ON sys_user_tenant_rel(tenant_id);

-- 4. 保留 1 条用户数据（假设保留 id=1 的 admin）
DELETE FROM sys_user WHERE id > 1;

-- 5. 插入 8 条关联关系（admin 关联所有租户）
INSERT INTO sys_user_tenant_rel (user_id, tenant_id)
SELECT 1, id FROM sys_tenant;

-- 6. 验证数据
SELECT '用户数' AS item, COUNT(*) AS count FROM sys_user
UNION ALL
SELECT '关联关系数', COUNT(*) FROM sys_user_tenant_rel
UNION ALL
SELECT '租户数', COUNT(*) FROM sys_tenant;
