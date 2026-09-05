# 审计日志入库设计文档（分级双轨 + admin 只读查询）

**日期:** 2026-09-05
**状态:** 待用户审批
**定位:** 纵深防御的「检测与归因」层——与既有预防层（ExecuteTool 白名单、env.clear、租户隔离）正交互补。前者"拦住坏事"，本设计"坏事万一发生了能查出来是谁、何时、对哪个租户的数据干的"。

---

## 1. 背景与现状

### 1.1 审计框架骨架已存在，但落库为 TODO

项目已具备审计链路的骨架：

```
@AuditLog 注解 → AuditLogAspect(AOP) → AuditLogService → AuditLogServiceImpl → TenantService.recordAuditLog
```

但最后一环 `TenantServiceImpl.recordAuditLog`（`company-rag-tenant/.../TenantServiceImpl.java:407-410`）是 **TODO 空实现**，仅 `log.info` 打印、**不落库**：

```java
@Override
public void recordAuditLog(String actionType, String targetType, String targetId, String detail) {
    // TODO: 审计日志表创建后再实现，先留空
    log.info("审计日志：action={}, target={}, id={}, detail={}", actionType, targetType, targetId, detail);
}
```

### 1.2 现有资产（可复用）

- `AuditLog` 实体（`tenant/model`，`@TableName("audit_log")`，含 tenantId/userId/ipAddress/createdAt）
- `AuditLogMapper`（`tenant/mapper`，继承 `BaseMapper<AuditLog>`）
- `audit_log_ddl.sql`（`tenant/src/main/resources/sql/`，建表脚本，幂等，字段齐全，含两个索引）

**⚠️ 现状缺陷（用户评审发现的关键硬伤，须在本设计修复）：**

1. **硬伤 1｜孤儿 DDL，表根本不会被创建**：`audit_log_ddl.sql` 位于 `company-rag-tenant/src/main/resources/sql/`，但 Flyway 的 `locations` 为 `classpath:db/migration`（`application.yml:85`），迁移目录（`company-rag-bootstrap/src/main/resources/db/migration/`）仅有 `V0~V3__*.sql`，不含 audit_log；`sql/init.sql`（docker 挂载）也不含。→ 该 DDL 是孤儿文件，上线后首次写审计即报 `relation "audit_log" does not exist`。

2. **硬伤 2｜schema 未限定，REQUIRES_NEW 会解析错表**：现有 DDL 与实体均为裸表名（无 `public.` 前缀）。租户请求下 `TenantSchemaInterceptor` 把连接 search_path 设为 `tenant_X`；`recordAuditLog` 用 REQUIRES_NEW 起新事务，从 HikariCP 可能拿到一条残留 `search_path=tenant_X` 的池化连接（该拦截器用 `SET` 而非 `SET LOCAL`，且注释明确"不依赖 HikariCP 归还时重置会话状态"）。→ `audit_log` 会被解析成 `tenant_X.audit_log`（不存在），写入失败或错写。与 `vector_store` 是同类教训。

3. **硬伤 3｜ignoreTable 未豁免 audit_log**：`TenantMyBatisPlusConfig.java:42-49` 的 `ignoreTable` 只豁免 `sys_tenant / sys_user / sys_user_tenant_rel`。→ 未豁免时 `TenantLineInnerInterceptor` 会对 audit_log 的 insert/select 自动追加 `tenant_id = ?`：写入被多塞一列/条件污染数据；admin 跨租户查询被错误截断，只能看到"当前租户"的审计。
- 已标注 `@AuditLog` 的控制器：`AuthController`（LOGIN/LOGOUT）、`UserController`（CREATE/UPDATE/DELETE_USER）、`TenantController`（CREATE/DELETE_TENANT）、`DocumentController`（DELETE_DOCUMENT）、`CacheManageController`（CLEAR_CACHE）
- `AuthController` 中还有两处手动 `tenantService.recordAuditLog(...)` 调用（LOGIN/LOGOUT）

### 1.3 暴露的问题

1. **信息断层**：AOP 目前只传 `actionType/targetType/targetId/detail`，实体/DDL 需要的 `tenant_id / user_id / ip_address` 取不到。
2. **落库缺失**：最后一环是 TODO，审计数据未持久化，"归因"无从谈起。

---

## 2. 目标与范围界定

**要做什么：**

- 把 `recordAuditLog` 的 TODO 补全为真实 DB 写入。
- 按操作特征分级处理（管理类同步、数据类异步批量）。
- 补全归属信息（tenant_id / user_id / ip_address）。
- **新增"技能/高风险工具"审计**：技能命令（ExecuteTool 的 PYTHON 分支）+ 高风险工具（`DatabaseQueryTool`/`DownloadTool`/外部 MCP）→ 写入既有 `audit_log`（actionType + detail=动作本体，不记输出）。
- **修复硬伤 1**：`audit_log` 建表迁移入 Flyway（新增 `V4__create_audit_log.sql`），删除孤儿 DDL。
- **修复硬伤 2**：写法规避 search_path 残留——`@TableName("public.audit_log")` 显式限定 public schema。
- **修复硬伤 3**：`TenantMyBatisPlusConfig.ignoreTable` 豁免 `audit_log`，避免 TenantLine 追加 `tenant_id` 污染写入、截断 admin 跨租户查询。
- 新增 admin 只读分页查询接口（按租户/用户/操作类型/时间过滤）。

**明确不做（YAGNI）：**

- ❌ 不做复杂审计前端界面（仅 REST 只读查询）。
- ❌ 不给租户开放审计查询（仅平台 admin）。
- ❌ 不做 detail 编辑/删除接口（审计只读不可变）。
- ❌ 不引入 ES/消息中间件做异步（轻量内存队列足够）。
- ❌ 不改 `audit_log` 表结构（现有字段已够用）。
- ❌ **不新增 `tool_call_log` 表**：工具调用的耗时/状态等全量明细维持 `ToolCallRecorder` 日志，审计表只记"高风险动作本体"。
- ❌ **不全部工具落库**：只读工具（`CodeSearchTool`/`ApiDocTool`/`KnowledgeBaseTool`/RAG 检索）**不落库**，仅 `ToolCallRecorder` 日志。

> 📌 **与 `ToolCallRecorder` 的分工**：`ToolCallRecorder`（common，通用）负责所有工具调用的 traceId/耗时/状态日志（内存 ThreadLocal，`RagAgentService` 聚合后打 `[AGENT] tools=[...]`）。本次审计**复用其"工具名"语义**、但只对高风险动作额外落库；两条链路不冲突、不重复建表。

---

## 3. 架构总览（分级双轨）

```
管理类(登录/登出、用户/租户/文档删除、缓存清空)     ── 同步直写 AuditLogMapper (REQUIRES_NEW)
数据类(RAG会话查询、文档上传/解析)                 ── 有界队列 → 后台批量 insertBatch
风险动作(技能命令/高风险管理工具/外部MCP)          ── 有界队列 → 后台批量 insertBatch
                                      │
                                      ▼
                 public schema.audit_log （平台级数据，仅 admin 可读，不受 RLS）
```

### 3.1 分级依据

- **管理类**：低频、对"谁干了什么"最敏感，需即时、可靠、独立于主事务的证据 → 同步 `REQUIRES_NEW` 直写。
- **数据类**：高频（尤其 RAG 会话查询），逐条同步写会拖慢主流程并导致表高速增长 → 有界队列 + 后台批量落库。
- **风险动作**：技能命令（ExecuteTool `python {skill}/{name}/scripts/*.py`）、`DatabaseQueryTool`、`DownloadTool`、外部 MCP 工具——对检测与归因最有价值，但频率高于管理类，走**异步批量**。只读工具（CodeSearch/ApiDoc/KnowledgeBase）不落库，仅 `ToolCallRecorder` 日志。

---

## 4. 组件设计

### 4.1 组件拆分

| 组件 | 归属模块 | 动作 | 职责 |
|---|---|---|---|
| `AuditLog`（实体） | tenant/model | **改** | `@TableName` 改为 **`public.audit_log`**（显式限定 public schema，规避硬伤 2） |
| `AuditLogMapper` | tenant/mapper | 已有，复用 | BaseMapper |
| `V4__create_audit_log.sql` | bootstrap/db/migration | **新增** | 建表迁移（修复硬伤 1）；删除孤儿 `sql/audit_log_ddl.sql` |
| `TenantMyBatisPlusConfig` | tenant/config | **改** | `ignoreTable` 增加 `audit_log` 豁免（修复硬伤 3） |
| `AuditLogContext`（新增 DTO） | common | **新增** | 载体：actionType/targetType/targetId/detail + 可选 tenantId/userId/ipAddress |
| `AuditLogService`（接口） | common | **改** | 暴露 `record(context)`（同步+REQUIRES_NEW）与 `recordAsync(context)`（入队） |
| `AuditLogAsyncWriter` | tenant | **新增** | 有界队列 + 后台批量 flush + `@PreDestroy` 兜底 |
| `AuditLogServiceImpl` | tenant | **改** | 注入 `AuditLogMapper`，落库；`recordAsync` 委托 AsyncWriter |
| `AuditLogAspect` | common | **改** | 采集 tenant/user/ip；按动作类型分发同步/异步 |
| `TenantServiceImpl.recordAuditLog` | tenant | **改** | 删除 TODO，委托 `AuditLogServiceImpl` 落库（保持 AuthController 兼容入口） |
| `AuditLogQueryService` | tenant | **新** | admin 分页过滤查询 |
| `AuditLogController` | web | **新** | admin 只读查询 REST |
| `ExecuteTool` | agent | **改** | `executeCommand` 执行后 `recordAsync`（技能命令+诊断），detail=命令本身 |
| `DatabaseQueryTool` | agent | **改** | 执行 SELECT 后 `recordAsync`，detail=SQL 语句 |
| `DownloadTool` | agent | **改** | 下载完成后 `recordAsync`，detail=目标 URL |
| 外部 MCP 工具调用点 | mcp-client | **改** | `ExternalMcpTool.execute` / `AgentToolRegistry.executeTool` 处 `recordAsync`，detail=工具名+参数 |
| `ToolCallRecorder` | common | 复用（不改） | 所有工具调用的 traceId/耗时/状态日志，只读工具不落库仅靠它留痕 |

### 4.2 接口签名（common）

```java
public interface AuditLogService {
    // 同步写，事务 REQUIRES_NEW（独立于主事务）
    void record(AuditLogContext context);
    // 异步批量写（入有界队列）
    void recordAsync(AuditLogContext context);
}
```

`AuditLogContext` 置于 common，避免接口参数爆炸，并让 `ExecuteTool`（agent 模块→依赖 common）能引用。

### 4.3 分级判定机制（明确无歧义）

为避免"按 actionType 前缀推断"带来的隐式与维护负担，分级由**注解显式声明**：

- 在 `@AuditLog` 注解新增 **`async` 属性，默认 `false`（同步/管理类）**。
- 数据类操作在标注处显式 `@AuditLog(..., async = true)`，AOP 据此调用 `recordAsync`。
- 非注解的显式调用（如 ExecuteTool）直接选择 `record()` 或 `recordAsync()`。

初始分级映射（供实现计划落实）：
- 同步（async=false，默认）：LOGIN、LOGOUT、CREATE/UPDATE/DELETE_USER、CREATE/DELETE_TENANT、DELETE_DOCUMENT、CLEAR_CACHE
- 异步（async=true）：RAG 会话查询、文档上传/解析、技能命令（EXECUTE_TOOL）、DatabaseQuery（DATABASE_QUERY）、Download（DOWNLOAD）、外部 MCP 工具（MCP_TOOL）

> 只读工具（CodeSearchTool / ApiDocTool / KnowledgeBaseTool / RAG 检索）**不在映射内**，不落库，仅靠 `ToolCallRecorder` 日志留痕。

### 4.4 工具/技能审计接入

按**混合方案**分级落库到既有 `audit_log`，**不加新表、不加新注解类型**：

- **技能命令**（`ExecuteTool.executeCommand`，`ExecuteTool.java:101`，PYTHON 分支）：执行后 `recordAsync`，`actionType=EXECUTE_TOOL`，detail=完整命令文本。技能本质上就是 execute 工具——无需为"技能"另设类型。
- **`DatabaseQueryTool`**：执行 SELECT 后 `recordAsync`，detail=SQL 语句。
- **`DownloadTool`**：下载后 `recordAsync`，detail=目标 URL。
- **外部 MCP 工具**：在 `ExternalMcpTool.execute` / `AgentToolRegistry.executeTool` 统一入口 `recordAsync`，detail=工具名+参数摘要，避免逐个 MCP 埋点。
- **detail 安全约束**：只记动作本体（命令/SQL/URL/工具名+参数），**不记输出、不记环境变量/密钥**（沿用 `ToolCallRecorder` 对 input 截断 50 字符的做法，避免敏感参数污染审计）。
- **依赖方向**：agent/mcp-client → common（已存在），无反向依赖；`AuditLogContext` 置于 common 供上述模块引用。
- **与 `ToolCallRecorder` 的分工**：`ToolCallRecorder` 仍负责所有工具调用的 traceId/耗时/状态日志（不重复建表）；本次审计只对**高风险动作**额外落库。两者不冲突。

---

## 5. 数据模型

沿用现有 `audit_log` 表，**不新增列、不改结构**；但为规避硬伤 2，实体 `@TableName` 改为 `public.audit_log`，DDL 中表名也统一带 `public.` 前缀（Flyway V4 建在 public schema）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | 自增 |
| `tenant_id` | VARCHAR(32) NOT NULL | 归属租户（审计回答"对哪个租户"） |
| `user_id` | BIGINT NOT NULL | 操作者 |
| `action_type` | VARCHAR(32) NOT NULL | LOGIN / DELETE_DOCUMENT / EXECUTE_TOOL / DATABASE_QUERY / DOWNLOAD / MCP_TOOL 等 |
| `target_type` | VARCHAR(32) | document / user / tenant / tool |
| `target_id` | VARCHAR(64) | 目标 ID |
| `detail` | TEXT | 详情（含 ExecuteTool 命令文本） |
| `ip_address` | VARCHAR(45) | 客户端 IP |
| `created_at` | TIMESTAMP | 默认 NOW() |

已建索引：`(tenant_id, action_type, created_at DESC)`、`(created_at DESC)`。

---

## 6. admin 只读查询接口

**`GET /api/admin/audit-logs`**

- 请求参数：`tenantId`（可选）、`userId`（可选）、`actionType`（可选）、`startTime`（可选）、`endTime`（可选）、`page`（默认 1）、`pageSize`（默认 20）
- 响应：`R<Page<AuditLog>>` 统一格式（分页，含总条数）
- 权限：仅 `ROLE_ADMIN`，其余角色 403（走既有 Spring Security 授权链）

---

## 7. 可靠性 / 安全 / 隔离

### 7.1 可靠性（分级）

**管理类（同步 REQUIRES_NEW）：**
- `@Transactional(propagation = REQUIRES_NEW)`，审计写入独立于主事务——主流程回滚不影响审计留存。
- AOP 环绕层自带 try/catch，审计失败仅 `log.warn`，**绝不抛给主流程**。

**数据类（异步批量）：**
- 有界 `BlockingQueue`（建议容量 1000），背压时丢弃并 `log.warn`（避免拖垮 RAG 主流程）。
- 后台单线程定时/按阈值刷库（每 100 条或每 500ms `batchInsert`）。
- `@PreDestroy` flush 残余，JVM 关闭不丢队列数据。

### 7.2 多租户隔离——关键风险点（已由设计决策修复）

`audit_log` 是 **public schema、平台级** 数据（admin 跨租户追溯），不受 RLS。硬伤 2/3 已通过两项决策规避：

- **硬伤 2（search_path 残留）**：所有访问统一 `public.` 显式前缀（实体 `@TableName("public.audit_log")`、Flyway V4 DDL、查询 SQL）。即便连接池残留 `search_path=tenant_X`，`public.audit_log` 因显式 schema 前缀仍正确解析到 public schema，与租户无关。
- **硬伤 3（TenantLine 追加 tenant_id）**：`TenantMyBatisPlusConfig.ignoreTable` 增加 `audit_log` 豁免，TenantLine 不再对审计表追加 `tenant_id` 条件，避免污染写入/截断 admin 跨租户查询。

> ⚠️ **实现硬约束**：审计相关的**所有 SQL（写入与查询）必须带 `public.` 前缀**，禁止出现裸 `audit_log` 表名，否则在多租户连接下会被 search_path 解析错。此条需在 boundaries / iron-rules 中显式记录。
>
> 补充：`TenantSchemaInterceptor` 仍会在审计写/查的连接上 `SET search_path`——因表名带 `public.` 前缀，无碍；但若某条审计 SQL 遗漏前缀，即会命中硬伤 2。这也是"统一前缀"约束的由来。

### 7.3 安全

- admin 接口走既有 Spring Security，`ROLE_ADMIN`。
- **工具/技能审计 detail 只记动作本体**（ExecuteTool 命令、DatabaseQuery 的 SQL、Download 的 URL、外部 MCP 工具名+参数摘要），**不记输出、不记环境变量/密钥**，并沿用 `ToolCallRecorder` 的 input 截断策略（50 字符）。
- 审计只读不可变，无编辑/删除接口。

---

## 8. 测试策略

- 遵循项目测试规范（正常 / 边界 / 异常）：

- `AuditLogServiceImplTest`：同步落库成功 / 失败降级（mock Mapper）；REQUIRES_NEW 独立事务验证。
- `AuditLogAsyncWriterTest`：队列背压丢弃、批量刷库、`@PreDestroy` flush。
- `AuditLogAspectTest`：归属信息（tenant/user/ip）补齐正确。
- `ExecuteToolTest`（扩展）：执行后调用 `recordAsync`，且命令不外泄（不记输出/密钥）。
- `DatabaseQueryToolTest` / `DownloadToolTest`（扩展）：执行/下载后调用 `recordAsync`，detail=SQL/URL。
- **外部 MCP 工具审计测试**：`ExternalMcpTool` / `AgentToolRegistry` 调用后 `recordAsync`（工具名+参数摘要）。
- **只读工具不落库测试**：验证 CodeSearch / ApiDoc / KnowledgeBase 调用**不**触发 `recordAsync`（仅 `ToolCallRecorder` 日志）。
- admin 查询 Controller 测试：过滤分页正确；非 admin 403。
- **Flyway 迁移测试**：启动上下文后验证 `public.audit_log` 已创建（表存在），孤儿 DDL 已删除。
- **多租户隔离测试**（关键，回归硬伤 2/3）：在租户 context（search_path=租户 schema）下写/查 `public.audit_log`，断言数据落到 public、不被租户隔离器过滤（仿 `RlsIsolationTest`、`TenantAwareJdbcTemplateTest` 模式）。

---

## 9. 风险点小结

1. **硬伤 1｜迁移落地**：须新增 `V4__create_audit_log.sql` 且**删除孤儿 `sql/audit_log_ddl.sql`**，否则表永不创建。已纳入范围。
2. **硬伤 2｜search_path 残留**：所有审计 SQL 强制 `public.` 前缀（实体/DDL/查询），否则被解析到租户 schema。已纳入范围，并作为实现硬约束（7.2）。
3. **硬伤 3｜多租户拦截器豁免 audit_log**：`ignoreTable` 增加豁免，否则租户/平台查询被误过滤、写入被污染。已纳入范围。
4. **失败留痕**：审计自身失败也应有 `log.error` 兜底，形成"审计的审计"。
5. **detail 敏感性**：命令文本可能敏感，仅 admin 可查。
6. **队列背压**：需有界 + 丢弃告警，避免内存溢出拖垮服务。