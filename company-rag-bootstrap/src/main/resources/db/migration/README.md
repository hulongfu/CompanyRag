# Flyway 迁移脚本目录

## 当前状态（重要，以代码为准）

⚠️ **Flyway 当前处于"未启用、未生效"状态。** 请勿依据本目录中任何"已启用"的旧表述判断。

- `CompanyRagApplication` 的 `@SpringBootApplication(exclude = ...FlywayAutoConfiguration.class)` 已显式**排除 Flyway 自动配置**。
- 因此 `application.yml` 及 dev/prod/test 中的 `spring.flyway.*` 配置**当前根本不会被读取**，`enabled: true` 只是配置值、并未被消费。
- **应用启动时不会执行任何数据库迁移，数据库不会被创建表、不会被 clean、不会被重置。**
- 当前数据库表结构由**运行时逻辑**维护：`TenantServiceImpl.createTenantSchema`（创建租户 schema/表）与 `tenantSchemaInitializer`（ApplicationRunner，自动建表）负责。

## 迁移脚本列表

| 版本 | 脚本名称 | 说明 | 建议执行时机 |
|------|---------|------|-------------|
| V1 | `V1__fix_tenant_isolation_security.sql` | 多租户隔离安全修复（建专用用户、修复 RLS 策略） | 首次正式启用迁移时 |
| V2 | `V2__fix_database_query_tool_cross_tenant_access.sql` | DatabaseQueryTool 跨租户访问漏洞修复（收回跨 schema 权限） | 首次正式启用迁移时 |
| V3 | `V3__init_platform_admin.sql` | 系统初始化（创建 admin 账号和默认租户、RLS 策略、权限） | 首次正式启用迁移时 |

## 为什么当前没有启用（背景）

历史上一度计划用 Flyway 管理生产库结构，但这些迁移脚本与项目"运行时自动建表"机制并存，且脚本内含硬编码用户/口令，直接启用会带来权限与结构冲突风险。为稳妥起见，**当前刻意保持不启用**（排除自动配置），仅保留脚本与正确前缀的配置作为存档，待下文问题解决后再启用。

## 如何启用 Flyway

启用 = 让 Flyway 自动配置真正生效。步骤如下（**切勿直接照做，需先解决下文"启用前必须解决的问题"**）：

1. 在 `CompanyRagApplication` 的 `@SpringBootApplication` 中**移除** `org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration` 的 `exclude`。
2. 确保 `application.yml`（含 dev/prod/test）保留 `spring.flyway.*` 配置（前缀必须是 `spring.flyway`，当前已修正）。
3. 核对以下关键配置项是否满足预期：
   - `locations: classpath:db/migration`（脚本路径）
   - `enabled: true`（启用开关）
   - `clean-disabled: true`（禁止 `clean`，防止清空数据库）
   - `baseline-on-migrate: true`、`baseline-version: 0`（对已有库做基线标记）
   - `validate-on-migrate: true`（校验脚本，避免已执行脚本被改动）
   - `out-of-order`（dev/test 可为 true，生产建议 false）
4. 重新部署后，启动日志应出现 Flyway 迁移执行记录；检查 `flyway_schema_history` 表确认已执行的版本。

## 启用前必须解决的问题（不解决勿启用）

以下问题会导致迁移失败或产生与运行时不一致的结构，**必须先解决**：

1. **连接用户权限不足（严重）**
   - V1:18 `CREATE USER company_rag_app WITH PASSWORD '...'`、V1 `GRANT CONNECT ON DATABASE company_rag` 需要相当权限。
   - 当前 `spring.datasource` 连接用户**未必具备**建用户 / GRANT / 建扩展（`CREATE EXTENSION vector`）的权限，挂在第一条迁移即失败。
   - 需明确：跑的迁移的连接用户 = 运行时应用用户，并确保该用户权限足够；或迁移用具备权限的管理员账号执行。

2. **明文字段与硬编码凭据（严重）**
   - V1:18 明文口令 `company_rag_app_password_change_me`；V3 固定 BCrypt 口令（对应 `admin123`）。
   - 一旦执行即固定落库，与"敏感信息走环境变量"的项目铁律冲突。应改为环境变量占位注入后启用。

3. **迁移脚本与运行时自动建表冲突（严重）**
   - V3 会 `CREATE SCHEMA tenant_tenant_default`、建表、建 HNSW 索引、`CREATE EXTENSION vector`。
   - 运行时 `TenantServiceImpl.createTenantSchema` / `tenantSchemaInitializer` 也会自动建同名 schema/表。两套机制会争建对象，`CREATE INDEX`（HNSW）等重复触发时可能因对象已存在而失败。
   - 需在"启用 Flyway 管理"与"运行时自动建表"之间**二选一**，避免双跑。

4. **schema 命名约定需对齐（警告）**
   - V3 默认租户 schema 为 `tenant_tenant_default`。若运行时实际生成的 schema 名不同（如 `tenant_default`），Flyway 建的对象与运行时使用的 schema 对不上，等于建了没用的库。
   - 需确认 `schema_name` 与运行时 `TenantSchemaInterceptor` / `createTenantSchema` 的命名完全一致。

5. **与已有库的 baseline 一致性（警告）**
   - 若在已有结构的库上启用，靠 `baseline-on-migrate` 标记基线后，V1-V3 是否与现库实际结构一致决定了 `validate-on-migrate` 是否通过。
   - 结构不一致会触发校验失败并拒绝启动。启用前需用测试/预发库演练验证。

## 新增迁移脚本规则

如需新增数据库变更，按以下规则创建脚本：

1. **命名格式**：`V<版本>__<描述>.sql`
   - 例如：`V4__add_user_avatar_column.sql`

2. **版本号规则**：
   - 必须严格递增（V1 → V2 → V3 → V4 ...）
   - 支持主.次版本：`V1_1__description.sql`

3. **幂等性要求**：
   - 使用 `CREATE TABLE IF NOT EXISTS`
   - 使用 `WHERE NOT EXISTS` 插入数据
   - 使用 `DROP ... IF EXISTS` 删除对象

4. **示例**：
   ```sql
   -- V4__add_user_avatar_column.sql
   ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(512);
   ```

5. **凭据一律走环境变量占位**（`${...}`），禁止明文口令。

## 验证迁移

启用后，检查数据库中的 `flyway_schema_history` 表：

```sql
SELECT installed_rank, version, description, type, script, state
FROM flyway_schema_history
ORDER BY installed_rank;
```

## 注意事项

- ⚠️ **迁移脚本一旦执行，不得修改**（只能新增）。
- ⚠️ **版本号必须严格递增**。
- ⚠️ **生产环境禁用 `clean` 操作**（已配置 `clean-disabled: true` 兜底）。
- ✅ 本目录脚本已按幂等规范书写（`IF NOT EXISTS` / `WHERE NOT EXISTS` / `IF EXISTS`），方向安全；但启用前仍需解决上文问题 1-3。

## 回滚方案

如果迁移失败：

1. **应用无法启动**：查看日志中的 Flyway 错误信息。
2. **数据问题**：从备份恢复数据库。
3. **脚本错误**：修复后创建新版本脚本（不要修改已执行的脚本）。

## 参考文档

- 官方文档：https://flywaydb.org/documentation/
- 项目安全修复报告：`docs/security-fixes/`