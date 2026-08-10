# Flyway 迁移脚本说明

## 迁移脚本命名规则

Flyway 迁移脚本遵循以下命名约定：

```
V<version>__<description>.sql
```

例如：
- `V1__init_schema.sql` - 初始化数据库 schema
- `V2__add_user_table.sql` - 添加用户表
- `V3__create_vector_store.sql` - 创建向量存储表

## 现有迁移脚本

现有 SQL 迁移脚本位于项目根目录的 `sql/migrations/` 目录下，需要按 Flyway 格式重命名并移动到：
- `src/main/resources/db/migration/` (classpath 迁移)
- 或保持 `./sql/migrations/` (filesystem 迁移，已在配置中启用)

## 迁移历史

Flyway 会自动跟踪已执行的迁移，记录在 `flyway_schema_history` 表中。

## 生产环境注意事项

1. **禁止使用 clean 命令**：`flyway.clean-disabled=true` 防止误删生产数据
2. **迁移脚本不可修改**：已执行的迁移脚本不得修改，只能新增迁移
3. **版本号递增**：每次迁移版本号必须严格递增
4. **回滚策略**：Flyway 社区版不支持自动回滚，需要手动编写回滚脚本

## 当前迁移脚本列表

待迁移的脚本：
- `sql/migrations/001-fix-tenant-isolation-security.sql` → `V1__fix_tenant_isolation_security.sql`
