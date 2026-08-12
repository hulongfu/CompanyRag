# Flyway 迁移脚本目录

## 当前状态

✅ **Flyway 已启用** - 用于生产环境数据库版本管理

## 迁移脚本列表

| 版本 | 脚本名称 | 说明 | 执行时机 |
|------|---------|------|---------|
| V1 | `V1__fix_tenant_isolation_security.sql` | 多租户隔离安全修复 | 首次部署时执行 |
| V2 | `V2__fix_database_query_tool_cross_tenant_access.sql` | DatabaseQueryTool 跨租户访问漏洞修复 | 首次部署时执行 |
| V3 | `V3__init_platform_admin.sql` | 系统初始化（创建 admin 账号和默认租户） | 首次部署时执行 |

## 配置说明

当前配置（`application.yml`）：

```yaml
flyway:
  enabled: true                    # 已启用
  locations: classpath:db/migration
  baseline-on-migrate: true        # 已有数据库时自动基线标记
  baseline-version: 0
  validate-on-migrate: true        # 校验迁移脚本
  clean-disabled: true             # 禁用 clean 操作（生产安全）
  migrate-at-startup: true         # 启动时自动执行迁移
```

## 新增迁移脚本

如需新增数据库变更，按以下规则创建脚本：

1. **命名格式**：`V<版本>__<描述>.sql`
   - 例如：`V4__add_user_avatar_column.sql`

2. **版本号规则**：
   - 必须严格递增（V1 → V2 → V3 → V4 ...）
   - 主版本.次版本格式：`V1_1__description.sql`

3. **幂等性要求**：
   - 使用 `CREATE TABLE IF NOT EXISTS`
   - 使用 `WHERE NOT EXISTS` 插入数据
   - 使用 `DROP ... IF EXISTS` 删除对象

4. **示例**：
   ```sql
   -- V4__add_user_avatar_column.sql
   ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(512);
   ```

## 执行流程

### 全新部署
1. Flyway 启动时自动执行 V1 → V2 → V3
2. 创建数据库用户、修复 RLS 策略、初始化 admin 账号
3. 应用正常启动

### 已有数据库
1. Flyway 检测到已有表结构
2. 自动执行基线标记（`baseline-on-migrate: true`）
3. **不会重新执行** V1-V3（幂等性保证）
4. 未来只执行新增的 V4、V5 等脚本

## 验证迁移

启动应用后，检查数据库中的 `flyway_schema_history` 表：

```sql
SELECT installed_rank, version, description, type, script, state 
FROM flyway_schema_history 
ORDER BY installed_rank;
```

## 注意事项

- ⚠️ **迁移脚本一旦执行，不得修改**（只能新增）
- ⚠️ **版本号必须严格递增**
- ⚠️ **生产环境禁用 `clean` 操作**（已配置 `clean-disabled: true`）
- ✅ 所有脚本都是幂等的，可安全重复执行

## 回滚方案

如果迁移失败：

1. **应用无法启动**：检查日志中的 Flyway 错误信息
2. **数据问题**：从备份恢复数据库
3. **脚本错误**：修复后创建新版本脚本（不要修改已执行的脚本）

## 参考文档

- 官方文档：https://flywaydb.org/documentation/
- 项目安全修复报告：`docs/security-fixes/`
