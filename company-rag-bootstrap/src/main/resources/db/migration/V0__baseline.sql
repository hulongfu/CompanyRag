# Flyway 基线脚本已删除

## 说明

Flyway 现在使用 `baseline-on-migrate: true` 配置自动处理基线标记，不需要手动创建 V0__baseline.sql 脚本。

## 工作原理

- **全新部署**：Flyway 自动创建 `flyway_schema_history` 表，并依次执行 V1 → V2 → V3
- **已有数据库**：Flyway 自动执行基线标记（版本 0），不会重新执行 V1-V3（幂等性保证）

## 删除时间

2026-08-12 - Flyway 正式启用时删除
