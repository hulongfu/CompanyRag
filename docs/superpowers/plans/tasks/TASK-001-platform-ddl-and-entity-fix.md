# TASK-001 平台级建表 + 实体/三硬伤修复

**模块:** 根/pom 平台、`company-rag-tenant`、`k8s`
**依赖:** 无（基础设施前置）

## 背景
设计文档原方案「Flyway V4 建表」不成立：`CompanyRagApplication` 排除了 `FlywayAutoConfiguration`，`db/migration/V*.sql` 全部不执行。平台级 `public` 表实际由 `sql/init.sql`（docker-compose）与 `k8s/initdb-configmap.yaml`（K8s）创建。本次据此修复三硬伤：①建表移入 init.sql + k8s；②实体 `@TableName` 用 `public.audit_log` 且存于 public schema；③ `TenantMyBatisPlusConfig.ignoreTable` 忽略 audit_log（平台级表）。

`audit_log` 为**平台级**表：存放所有租户的审计记录。因此存储于 `public` schema，不落入任一租户 schema，且需加入 `ignoreTable` 豁免。`tenant_id` 为 `NOT NULL`（设计 §5），登录时取 `SecurityUser` 首个租户（见 TASK-004）。

## Files
- Modify: `sql/init.sql`（public 平台表区，sys_tenant/sys_user 之后追加 `public.audit_log`）
- Modify: `k8s/initdb-configmap.yaml`（同款建表，内容对齐 sql/init.sql）
- Modify: `company-rag-tenant/.../model/AuditLog.java`（`@TableName("public.audit_log")`）
- Modify: `company-rag-tenant/.../config/TenantMyBatisPlusConfig.java`（ignoreTable 加 audit_log）
- Delete: `company-rag-tenant/src/main/resources/sql/audit_log_ddl.sql`（孤儿，裸表名）
- Archive(不执行): `db/migration/V4__create_audit_log.sql`

## 附件：audit_log 表结构（与设计文档 §5、实体字段一一对应，含列类型约束）

```sql
CREATE TABLE IF NOT EXISTS public.audit_log (
    id          BIGSERIAL PRIMARY KEY,
    -- 归属租户（审计回答"对哪个租户"）；登录时取 SecurityUser 首个租户，非空
    tenant_id   VARCHAR(32)  NOT NULL,
    -- 操作者
    user_id     BIGINT       NOT NULL,
    -- LOGIN / DELETE_DOCUMENT / EXECUTE_TOOL / DATABASE_QUERY / DOWNLOAD / MCP_TOOL 等
    action_type VARCHAR(32)  NOT NULL,
    target_type VARCHAR(32),
    target_id   VARCHAR(64),
    detail      TEXT,
    ip_address  VARCHAR(45),
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);
-- 查询索引（匹配 admin 按租户+操作类型+时间过滤）
CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_action_time ON public.audit_log (tenant_id, action_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON public.audit_log (created_at DESC);
```
> 字段与实体 `AuditLog` 一一对应（`tenantId` String→`tenant_id`、`userId` Long→`user_id`、`ipAddress`→`ip_address`、`createdAt`→`created_at`）。DDL 类型含 NOT NULL 约束，实体写入必须保证 tenant_id/user_id/action_type 非空。

---

- [ ] **Step 1: 写失败测试（表结构契约）**
	- 新建 `company-rag-tenant/src/test/java/com/company/rag/tenant/model/AuditLogTableStructTest.java`：读 `AuditLog.java` 的 `@TableName` 注解值，断言等于 `public.audit_log`（红：当前未改，注解为 `audit_log`）。
	- 由测试断言 `AuditLog` 关键字段存在（`tenantId/userId/actionType/targetType/targetId/detail/ipAddress/createdAt`），与 SQL 列一一对应。

- [ ] **Step 2: 实现**
	1. `sql/init.sql`：`CREATE TABLE IF NOT EXISTS public.audit_log (...)` + 两索引。追加在 `public.sys_user` 建表段之后（平台表区），加中文表头注释 `-- 审计日志表（平台级，tenant_id NOT NULL，登录取 SecurityUser 首个租户）`。
	2. `k8s/initdb-configmap.yaml`：`init.sql: |-` 内追加同款建表（内容与 sql/init.sql 对齐）。
	3. `AuditLog.java`：`@TableName("audit_log")` → `@TableName("public.audit_log")`；确认实体含 `@TableField` 映射 SQL 列（字段以实体现有定义为准，不新造）。
	4. `TenantMyBatisPlusConfig.java` ignoreTable 加 `|| "audit_log".equalsIgnoreCase(tableName)`（返回 true 忽略租户过滤）。
	5. 删除孤儿 `company-rag-tenant/src/main/resources/sql/audit_log_ddl.sql`。
	6. `V4__create_audit_log.sql` 顶部加注释头标注「存档脚本，Flyway 未启用，不执行；实际由 init.sql / k8s 建表」。

- [ ] **Step 3: 验证（绿）**
	- 单测：`mvn -q -pl company-rag-tenant test -Dtest=AuditLogTableStructTest`
	- 内容断言（表已建）替代真实 PG：
		- `grep -n "CREATE TABLE IF NOT EXISTS public.audit_log" sql/init.sql k8s/initdb-configmap.yaml` 均应命中。
		- `grep -n "audit_log" company-rag-tenant/src/main/java/com/company/rag/tenant/config/TenantMyBatisPlusConfig.java` 命中 ignoreTable 分支。

- [ ] **Step 4: 提交**
	- 提交信息：`feat(audit): 按 init.sql/k8s 建 public.audit_log 平台级审计表（修正 Flyway 未启用的建表机制）`
	- 提交范围：仅本任务文件清单。

---

## 风险点（涉及数据库/权限，须明确）
- 【高危】**既有库不重跑 initdb**：initdb 仅新库首启执行。对本任务改动的已存在数据库，`public.audit_log` 不会自动创建。**上线步骤必须包含：在既有 PG 手工执行一次附件 DDL**（含索引）。已在 index.md 风险提示登记，提交前请向用户确认已具备既有库升级路径。
- 【中】`public.` 前缀：`@TableName("public.audit_log")` 使 MyBatis-Plus 拼接 `INSERT INTO public.audit_log`。需确认运行库的 `search_path` 不影响显式 schema 限定（显式 public. 前缀规避）。
- 【中】ignoreTable 遗漏会导致 INSERT 被追加 `tenant_id=?` 条件且 update/delete 被拦——本任务已覆盖，TASK-002/006 验证时回归 `RlsIsolationTest`。