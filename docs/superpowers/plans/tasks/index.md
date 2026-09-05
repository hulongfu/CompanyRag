# 审计日志持久化 实施计划索引

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Each task file uses checkbox (`- [ ]`) syntax for tracking. Review each task file with the user before implementation (spec → review → plan → review → code).
>
> 上游设计: `docs/superpowers/specs/2026-09-05-audit-log-persistence-design.md`

**Goal:** 将现有「仅 log.info 打点」的审计日志落库，覆盖 认证事件、工具调用、Python 技能执行、外部 MCP 调用，提供 admin 只读查询，并对齐 Flyway 未启用导致的建表机制事实。

**关键事实（计划输入，已核实）：**
- Flyway 在 `CompanyRagApplication` 被整体排除（`exclude FlywayAutoConfiguration`），`db/migration/V*.sql` 全部不执行。平台级 `public` 表（`sys_tenant`/`sys_user`）由 `sql/init.sql`（docker-compose）与 `k8s/initdb-configmap.yaml`（K8s 初始化）创建。**故符合设计意图的建表方式是 方案1：在 `sql/init.sql` + `k8s/initdb-configmap.yaml` 的 public 平台表区新增 `public.audit_log`，删孤儿 DDL，保留 V4 脚本存档。**
- `audit_log` 为平台级表（`public` schema，存所有租户审计，`tenant_id NOT NULL`），必须加入 `TenantMyBatisPlusConfig.ignoreTable` 豁免，否则被 TenantLine 追加 tenant_id 污染写入/截断 admin 跨租户查询。
- 已存在的既有数据库不会重跑 initdb（initdb 仅新库首启执行）——**上线需手工在既有库执行一次建表 DDL**（方案1完工风险）。

**方案决策（已与用户确认）：**
1. **建表机制**：audit_log 加入 `sql/init.sql` + `k8s/initdb-configmap.yaml`（public 平台表区，`IF NOT EXISTS` + `public.` 前缀 + 两索引），删孤儿 `company-rag-tenant/.../sql/audit_log_ddl.sql`，`V4__create_audit_log.sql` 保留存档。实体 `@TableName("public.audit_log")`，`TenantMyBatisPlusConfig.ignoreTable` 加 `audit_log`。**不启用 Flyway**（避免连带执行 V1-V3 明文凭据/权限/争建对象的连环坑）。
2. **落库核心**：`AuditLogContext`(common) 携带归属；`AuditLogService` 扩展 `record(context)`（同步）+ `recordAsync(context)`（入队）；`AuditLogAsyncWriter`(tenant) 有界队列 + 批量落库 + `@PreDestroy` 兜底；`AuditLogServiceImpl`(tenant) 注入 `AuditLogMapper` 自营落库。
3. **认证事件（方案A）**：`AuthController.login/logout` 改走 `auditLogService.record(...)` 同步，归属取 `SecurityUser`；`TenantServiceImpl.recordAuditLog`(4参 todo) 委托 `AuditLogServiceImpl`。
4. **工具/技能/MCP（异步）**：`ExecuteTool.executeCommand`、`DatabaseQueryTool.queryDatabase`、`DownloadTool.execute` 埋点 `recordAsync`；外部 MCP 在 `AgentToolRegistry.executeTool` 统一落库。只读工具（CodeSearch/ApiDoc/KnowledgeBase）不落库。
5. **admin 查询**：`AuditLogQueryService`(tenant) + `AuditLogController`(web) `GET /api/admin/audit-logs` 返回 `R<Page<AuditLog>>`（`ROLE_ADMIN`）；前端 `audit-log.html` + `index.html` 入口。

## 任务依赖

```
TASK-001  平台级建表 + 实体/三硬伤修复（init.sql + k8s + @TableName + ignoreTable）
   │
   ▼
TASK-002  落库核心：AuditLogContext + AuditLogService 扩展 + AuditLogAsyncWriter + AuditLogServiceImpl
   │
   ├─ TASK-003  AOP：AuditLogAspect 归属采集 + @AuditLog.async + 同步/异步分发   [依赖 002]
   ├─ TASK-004  方案A：AuthController login/logout + TenantServiceImpl 委托      [依赖 002]
   ├─ TASK-005  工具/技能/MCP 审计接入（ExecuteTool/DatabaseQuery/Download/AgentToolRegistry）[依赖 002]
   └─ TASK-006  admin 只读查询（QueryService + Controller + 前端）              [依赖 001, 002]
```

## Task 文件

| # | 文件 | 模块 | 内容 |
|---|------|------|------|
| TASK-001 | `TASK-001-platform-ddl-and-entity-fix.md` | 根/平台/tenant/k8s | public.audit_log 建表 + 实体 @TableName + ignoreTable + 删孤儿 + V4 存档 |
| TASK-002 | `TASK-002-audit-core-persistence.md` | common/tenant | AuditLogContext + Service 扩展 + AsyncWriter + ServiceImpl 落库 |
| TASK-003 | `TASK-003-audit-aspect-coordination.md` | common | AuditLogAspect 归属采集 + @AuditLog.async + 同步/异步分发 |
| TASK-004 | `TASK-004-auth-event-audit-option-a.md` | web/tenant | AuthController login/logout + TenantServiceImpl.recordAuditLog 委托 |
| TASK-005 | `TASK-005-tool-and-skill-audit.md` | agent/mcp-client | ExecuteTool/DatabaseQueryTool/DownloadTool/AgentToolRegistry(外部MCP) 埋点 |
| TASK-006 | `TASK-006-audit-query-admin.md` | tenant/web | AuditLogQueryService + AuditLogController + audit-log.html + index 入口 |

## TDD 验证（最窄命令基准）

多模块需先装依赖，再跑目标模块单/多测试类：
```bash
# 一次性编译/安装被依赖模块（tenant/web 依赖 common/tenant）
mvn -q -pl company-rag-common,company-rag-tenant install -DskipTests

# tenant 模块测试（多租户回归基准：RlsIsolationTest、TenantAwareJdbcTemplateTest）
mvn -q -pl company-rag-tenant test -Dtest=RlsIsolationTest,TenantAwareJdbcTemplateTest,<新增测试类>

# agent 模块测试
mvn -q -pl company-rag-agent test -Dtest=<新增测试类>

# web 模块测试
mvn -q -pl company-rag-web test -Dtest=<新增测试类>
```
> 不跑全量单测（`mvn test` 无过滤器）以免超时；仅跑本次改动的测试类。001 建表的「表已建」断言需真实 PG（`sql/init.sql` / k8s 无法在单测内执行），以 SQL 文件内容断言 + 集成验收替代（见 TASK-001 验证）。

## 风险提示（提交代码前请用户知悉）
- 既有库不重跑 initdb：上线需手工在既有 PG 执行一次 `CREATE TABLE public.audit_log ...`（见 TASK-001 附件 DDL）。
- `audit_log` 走 `public` schema + `ignoreTable`，查询需防止被租户行级/列级插件干扰；只读查询严格带 `tenant_id` 过滤或 `WHERE tenant_id IS NULL`，避免跨租户泄露。
- 异步队列：进程异常退出可能丢队列内未落库日志（有界队列 + @PreDestroy 兜底缓解，非严格保证）。