# TASK-005 工具/技能/MCP 风险动作审计接入

**模块:** `company-rag-agent`、`company-rag-mcp-client`
**依赖:** TASK-002（AuditLogService.recordAsync + AuditLogContext）

## 背景
设计 §4.1/4.4 + 混合方案：**高风险风险动作**异步批量落库，**只读工具**（CodeSearch/ApiDoc/KnowledgeBase/RAG 检索）仅 `ToolCallRecorder` 日志、**不落库**。不新增表、不新增注解——动作本体写既有 `audit_log.detail`。

已核实现状与埋点（design §4.1，逐条）：
- `ExecuteTool.executeCommand`（agent，L129 @Tool "execute"，`executeCommand` 统一入口：PYTHON/LIST/READ/PWD/ECHO）→ 执行后 `recordAsync`，detail=命令本身。
- `DatabaseQueryTool.queryDatabase`（agent）→ SELECT 成功后 `recordAsync`，detail=清洗后 SQL（去注释/危险检测后的 `qualifiedSql` 或原 cleanSql）。该工具用 `TenantContext.getSchema()/getUserId()`（非 SecurityContext）。
- `DownloadTool`（agent）→ 下载完成后 `recordAsync`，detail=目标 URL。
- 外部 MCP → `ExternalMcpTool.execute`（mcp-client）+ `AgentToolRegistry.executeTool`（agent，L59 统一入口）→ `recordAsync`，detail=工具名+参数。
- `ToolCallRecorder`（common）**复用不改**——只读工具留痕靠它，审计链路不与其冲突。

归属：`AuditLogContext.tenantId`/`userId` 需从 SecurityUser / TenantContext 取（工具执行通常在已认证请求的 agent 线程内）。**各工具归属来源可能不同**：DatabaseQueryTool 用 TenantContext；ExecuteTool/DownloadTool 若拿不到 SecurityContext，优先 TenantContext（取其 schema 反推租户不可行，tenant 取 TenantContext.getTenantId()/getUserId()——若存在）。

> ❗ **前置确认（提交前须核实）**：各工具所在线程在 agent 执行时是否已建立 `TenantContext`（含 tenantId/userId）与 `SecurityContext`。若两者都拿不到归属，则 recordAsync 会落 `tenant_id/user_id NOT NULL` 失败（record 内 catch 吞掉、审计丢失）。**本任务执行时先写一个小探针/单测确认归属源可得，再埋点。**

---

- [ ] **Step 1: 写失败测试（TDD）**

**`company-rag-agent/src/test/.../tool/` 新建 per-tool 审计测试**（或集中 `ToolAuditTest`，设计 §8）：
- `ExecuteTool`：调 `executeCommand("python scripts/a.py")`（PYTHON 白名单路径）→ mock `AuditLogService` → verify `recordAsync(any())`，捕获 context 断言 `detail` 含命令、`actionType=EXECUTE_TOOL`。
- 拒绝命令不落：`executeCommand` 命中拒绝（`rejectUnsafePath` 返回非 null，返回"错误：..."）→ verify `recordAsync` **not** called（拒绝执行无动作本体可留痕，或按设计可留痕——TDD 先锁定语义后实现对齐）。**注：设计 §4.1 写"执行后 recordAsync"；按语义倾向只对放行且执行的命令留痕，拒绝的命令不进审计（避免把拒绝理由误当已执行）。单测据此锁定，若需审计拒绝事件由用户拍板。**
- `DatabaseQueryTool`：mock JdbcTemplate 返回结果 → verify `recordAsync`，detail=SQL、actionType=DATABASE_QUERY、归属来自 TenantContext。
- `DownloadTool`：mock 下载成功 → verify `recordAsync`，detail=URL、actionType=DOWNLOAD。
- 外部 MCP：`AgentToolRegistry.executeTool`/`ExternalMcpTool.execute` → verify `recordAsync`，detail=工具名+参数、actionType=MCP_TOOL。

- [ ] **Step 2: 实现**

**0) 归属解析辅助**：在各工具内读取归属。建议在 agent 建一个私有 help `AuditLogContext buildToolContext(actionType, detail)`，统一从 `TenantContext.getTenantId()/getUserId()`（若该工具已用 TenantContext）或 `SecurityContextHolder` 取，null 兜底。

**1) `ExecuteTool`（agent/tool，L129 executeCommand）**：
- 注入 `AuditLogService`（common，agent 依赖 common，可行）。
- 在 `switch` 后、`executeCommand` 返回前，仅当命令被**放行且实际执行**（`rejectUnsafePath` 通过、命中 PYTHON/LIST/READ/PWD/ECHO 分支）时：
```java
auditLogService.recordAsync(AuditLogContext.builder()
    .actionType("EXECUTE_TOOL")
    .targetType("tool").targetId("execute")
    .detail("command=" + commandStandardized(command))   // 命令本身，含技能 python 脚本；不记输出
    .userId(...).tenantId(...).build());
```
- `commandStandardized`：沿用 ToolCallRecorder 思路截断敏感信息，但**命令本体要留痕**，做长度上限（如 500，防超长命令）即可；技能命令保留脚本相对路径。

**2) `DatabaseQueryTool`（agent/tool）**：
- 注入 `AuditLogService`。
- `queryDatabase` 成功后（`jdbcTemplate.queryForList` 无异常）`recordAsync`：
```java
.actionType("DATABASE_QUERY").targetType("tool").targetId("queryDatabase")
.detail(cleanSqlOrQualifiedSql)   // 清洗后 SQL，去注释/危险检测后的可执行片段
.userId(TenantContext.getUserId()).tenantId(TenantContext.getSchema()!=null?TenantContext.getSchema():null)
// 注意：tenant_id 存租户标识，TenantContext.getSchema() 是 tenant_<code>。若表要求 tenant 短标识，需换算；归属问题上与 TASK-006 admin 查询语义对齐。
```
> ⚠️ **归属字段契约**：`tenant_id` 存什么值必须全链路一致（design §5 是 `VARCHAR(32)`，原存 business tenantId 的字符串形态）。schema=`tenant_<code>` 与租户 id 是否等价，**执行前需与 TenantContext 现有字段核对**，避免 audit 与 admin 查询对不上。若 TenantContext 有独立 tenantId 字段优先用它；否则记录 schema（`tenant_X`）。**列为 TASK-005 关键决策点。**

**3) `DownloadTool`（agent/tool）**：下载成功后 `recordAsync`，`actionType=DOWNLOAD`，detail=目标 URL（截断）。

**4) 外部 MCP `AgentToolRegistry.executeTool`（agent/tool, L59）+ `ExternalMcpTool.execute`（mcp-client）**：
- `recordAsync`，detail=`name + 参数摘要`（ToolCallRecorder 50 字符截断思路），actionType=`MCP_TOOL`。
- 归属同第 0 步；外部 MCP 工具按 params 名判断是否高风险，README 标注仅高风险外部 MCP 落库。

- [ ] **Step 3: 验证（绿）**
```bash
mvn -q -pl company-rag-common,company-rag-tenant install -DskipTests
mvn -q -pl company-rag-agent install -DskipTests -am     # agent 依赖 common/tenant
mvn -q -pl company-rag-agent test -Dtest=ToolAuditTest,AgentToolRegistryTest
mvn -q -pl company-rag-mcp-client test -Dtest=HttpMcpClientIntegrationTest   # 若涉及
```

- [ ] **Step 4: 提交**
- 提交信息：`feat(audit): 高风险工具/技能/外部MCP 异步审计接入`
- 提交范围：agent ExecuteTool/DatabaseQueryTool/DownloadTool/AgentToolRegistry + mcp-client ExternalMcpTool + 测试。

---

## 风险点
- 【高】**归属缺失 → 写入失败**：`audit_log.tenant_id/user_id NOT NULL`。工具在 agent 线程执行，若 `TenantContext`/`SecurityContext` 未建立，context 归属为 null → record 内 catch 吞掉、审计静默丢失。**TASK-005 首个前置子任务：实证某一次真实 agent 工具调用的 TenantContext/SecurityContext 填充情况；若缺失，需在 recordAsync 前从调用链带入归属，或对 recordAsync 的 tenant 缺失做加权（design 允许 tenant NULL？design §5 是 NOT NULL）——需向用户确认归属缺失时的取舍（报错 or 记录 to 平台 schema）。**
- 【高】**归属语义不一致**：ExecuteTool（SecurityUser 体系）vs DatabaseQueryTool（TenantContext schema）混用，`tenant_id` 存的值口径需统一（见 Step2-2 决策点）。若不统一，admin 按 tenant 过滤会遗漏。
- 【中】拒绝命令不落 vs 落：语义锁定在 TDD，但需用户认可"仅放行且执行才留痕"，否则拒绝对抗（嫌疑行为被拦）无法事后查证——**建议设计补充：拒绝事件也可落（detail=拒绝原因），交由用户拍板 TASK-005 采纳**。
- 【低】外部 MCP 的 mcp-client 是否依赖 common 的 `AuditLogService`：需确认 mcp-client pom 依赖关系；若不依赖，外部 MCP 审计经 agent 的 `AgentToolRegistry` 统一入口实现（避开跨模块依赖），ExternalMcpTool 如需直埋另行评估。