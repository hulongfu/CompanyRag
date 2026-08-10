# SQL 迁移脚本目录

## 说明

本目录存放手动执行的 SQL 迁移脚本，用于数据库初始化和结构变更。

**重要提示**：Flyway 数据库版本管理工具当前已禁用，所有 SQL 脚本需要手动执行。

## 脚本列表

### V1__fix_tenant_isolation_security.sql

**用途**：多租户隔离安全修复脚本

**执行内容**：
1. 创建专用数据库用户 `company_rag_app`（非超级用户）
2. 授予 public schema 系统表权限
3. 为现有租户 schema 授权
4. 设置默认权限（未来创建的表自动授权）
5. 修复 RLS 策略（移除 postgres 后门，增加 WITH CHECK + FORCE RLS）

**执行方法**：

```bash
# 方法一：命令行执行
psql -h localhost -U postgres -d company_rag -f V1__fix_tenant_isolation_security.sql

# 方法二：DBeaver GUI 执行
# 1. 打开 DBeaver → 连接数据库
# 2. 右键本文件 → 执行脚本
```

**注意事项**：
- 脚本中第 3 部分和第 4 部分会遍历所有 `tenant_*` 开头的 schema
- 如果还没有创建租户 schema，这部分会跳过
- 执行后请修改默认密码：`company_rag_app_password_change_me`

## 执行顺序

如果有多个迁移脚本，按版本号顺序执行：

```bash
# 1. 先执行 V1
psql -h localhost -U postgres -d company_rag -f V1__fix_tenant_isolation_security.sql

# 2. 再执行 V2（如果有）
psql -h localhost -U postgres -d company_rag -f V2__your_migration.sql
```

## 与 Flyway 的关系

当前 Flyway 已禁用，原因：
- 开发环境手动执行更灵活
- 生产环境部署时再考虑启用 Flyway

**未来启用 Flyway 时**：
1. 将本目录的 SQL 脚本移动到 `company-rag-bootstrap/src/main/resources/db/migration/`
2. 确保命名符合 Flyway 规范：`V<版本>__<描述>.sql`
3. 修改 `application.yml`：`flyway.enabled: true`
4. 应用启动时会自动执行迁移

详见：`../../company-rag-bootstrap/src/main/resources/db/migration/README.md`

## 验证执行结果

执行以下 SQL 验证修复是否成功：

```sql
-- 检查用户是否存在
SELECT rolname, rolsuper, rolcreaterole, rolcreatedb 
FROM pg_catalog.pg_roles 
WHERE rolname = 'company_rag_app';

-- 检查 RLS 策略定义
SELECT schemaname, tablename, policyname, qual 
FROM pg_policies
WHERE policyname LIKE 'tenant_isolation_%'
ORDER BY schemaname, tablename;
```

## 回滚方法

如果执行后需要回滚：

```sql
-- 1. 删除创建的用户（谨慎操作）
DROP USER IF EXISTS company_rag_app;

-- 2. 恢复 RLS 策略（需要手动还原到之前的版本）
-- 请参考执行前的备份脚本
```

**建议**：执行重要迁移脚本前先备份数据库！

```bash
# 备份整个数据库
pg_dump -h localhost -U postgres -d company_rag -f backup_$(date +%Y%m%d).sql

# 恢复数据库
psql -h localhost -U postgres -d company_rag -f backup_20260810.sql
```
