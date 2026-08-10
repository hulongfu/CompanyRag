# Flyway 迁移脚本目录

## 当前状态

Flyway 功能当前已禁用。此目录为空，保留用于未来可能的数据库版本管理需求。

## 如何启用 Flyway

1. 修改 `application.yml` 配置：
   ```yaml
   flyway:
     enabled: true  # 改为 true
   ```

2. 在此目录放置 SQL 迁移脚本，命名格式：
   ```
   V<版本>__<描述>.sql
   例如：V1__create_user_table.sql
   ```

3. 首次启用时，如果数据库已有表结构，确保配置：
   ```yaml
   flyway:
     baseline-on-migrate: true
     baseline-version: 0
   ```

## 现有 SQL 脚本

原始的 SQL 脚本保留在项目根目录的 `sql/migrations/` 目录，可以手动执行。

## 注意事项

- 迁移脚本一旦执行，不得修改（只能新增）
- 版本号必须严格递增
- 生产环境禁用 `clean` 操作
