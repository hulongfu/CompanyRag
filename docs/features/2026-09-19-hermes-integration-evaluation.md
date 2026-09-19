# Hermes × OpenClaw → CompanyRag 候选整合功能评估报告

> 说明：本文档为**评估产物**，用于盘点 hermes-openclaw-agent 具备的、可「扩展/优化」CompanyRag 的功能，侧重**可移植性、改动面、风险**。本次会话仅产出评估，不实现代码。
> 评估日期：2026-09-19。

## 0. 评估基线

- **目标项目**：CompanyRag（Spring Boot 3.4 + Spring AI **1.0 + Alibaba ReactAgent/Graph** + PGVector + MyBatis-Plus + Resilience4j + Micrometer）
- **源项目**：hermes-openclaw-agent（Spring Boot 3.4.5 + Spring AI **1.1.0** + JPA + 自定义 `AgentOrchestrator`）
- **迁移分层**：每项标注 **A 直接移植 / B 参考改造 / C 仅借鉴思路**。
- 关键差异：两套 Agent 底层不同——CompanyRag 用 Spring AI Alibaba 的 `ReactAgent`（黑盒），hermes 用自定义 `AgentOrchestrator`（thought→tool→observe 自写循环 + 全量 trace），且持久层 CompanyRag 为 MyBatis-Plus、hermes 为 JPA。因此**大部分源实现只能参考改造，直接拷代码不可行**。

## 1. 候选整合点总览

| # | 功能 | 源实现 | 迁移分层 | 对 CompanyRag 价值 | 改动量 |
|---|------|--------|---------|--------------------|--------|
| 1 | 人类审批门 Approval | `ApprovalService`+`ToolArgsApprovalPolicy`+回调拦截 | B | 保护 `DatabaseQueryTool`/`ExecuteTool` 高风险操作 | 中 |
| 2 | 透明运行轨迹 Trace | `TraceStep`+`AgentOrchestrator` 逐步追加 | B（轻量版）/B（完整版） | 当前 ReactAgent 黑盒，可观测性提升 | 低-中 / 高 |
| 3 | 事件溯源审计 Audit | append-only `AuditEvent` | B | 血缘可重建 | 中 |
| 4 | Mock LLM 离线模式 | `MockChatModel`/`MockEmbeddingModel` | A | 离线/CI/演示全链路，直击 A1 测试痛点 | 低 |
| 5 | 多厂商独立配置 | chat/embedding/rerank 分离 | A（思路已有） | 现状基本已具备 | 无需做 |
| 6 | 声明式 Persona | `resources/agent/*.md` | B | 已有系统提示词模板替代 | — |
| 7 | Skill 技能注册+自改进 | `SkillRegistry`+`SkillMemoryService` | C | 与当前定位关联弱，含执行风险面 | — |
| 8 | Heartbeat 自主心跳 | `HeartbeatScheduler` | B | 与检索问答定位关联弱 | — |

## 2. 逐项评估

### 2.1 人类审批门 Approval（P0，建议优先）

- **源实现**：`approval/ApprovalService.gate()`（pending→approve/deny/re-execute，支持 autoApprove）、`ToolArgsApprovalPolicy`（工具名+参数 JSON 决定是否需审批）、`AgentToolCallback` 在工具执行前被拦截。
- **可移植性**：B（参考改造）。审批状态机思路可完整沿用；落库需 JPA→MyBatis-Plus，且**拦截点必须嵌入 CompanyRag 实际执行路径**（见 §3）。
- **改动面**：新增审批实体/表/Mapper + `ApprovalService` + Controller（含前端审批面板）。
- **风险**：审批为阻塞式，需规划超时/过期；`ExecuteTool` 即便审批仍是高危入口，审批门不能替代权限校验，需叠加（铁则：不绕过安全校验）。

### 2.2 透明运行轨迹 Trace（P1 → 分层）

- **源实现**：`AgentOrchestrator` 每轮追加 `TraceStep(phase/kind/content)`，SSE `/chat/stream` 逐步推送。
- **可移植性**：B。CompanyRag 的 `ReactAgent` 是黑盒，拿不到「思考」逐步片段；`StreamingAgentExecutor` 注释亦明确 ReactAgent 非流式。
  - **轻量版**：仅在工具调用层记录入参/出参/耗时（现有 `ToolCallRecorder`+审计可低成本拼出 TOOL 类 trace，拿不到 THOUGHT）。
  - **完整版**：需按 hermes 重写 `AgentOrchestrator` 自循环，改动大、动 Agent 核心。
- **结论**：先轻量版，完整版列为后续。

### 2.3 事件溯源审计 Audit（P1）

- **源实现**：`AuditEvent` append-only + 仓库可重建会话血缘。
- **可移植性**：B。CompanyRag 现有 `AuditLogService`（异步落库）+ 工具调用审计（调用即落一条），已覆盖绝大部分，仅非事件溯源、无重建能力。
- **风险**：低，与现有审计架构兼容，主要在迁移与接口补齐。

### 2.4 Mock LLM 离线模式（P0，成本最低）

- **源实现**：`MockChatModel`/`MockEmbeddingModel` 实现 Spring AI `ChatModel`/`EmbeddingModel`，`llm.mode=mock|remote` 切换，零外部调用可跑通全链路。
- **可移植性**：A（可直接移植）。Spring AI 接口在 1.0 与 1.1 基本一致；仅需适配模式切换与 bean 互斥。
- **价值**：直接改善 known-improvements 中 **A1「PG 真库集成测试依赖外部 LLM」**，让离线/CI 可跑全链路。

### 2.5 多厂商独立配置（无需做）

- CompanyRag ARCHITECTURE.md 已注明「LLM+Embedding 双供应商可插拔」，与 hermes 目标一致，**现状已具备**。

### 2.6-2.8 Persona / Skill / Heartbeat（跳过）

- Persona：有系统提示词模板替代，企业 RAG 场景命中率低。
- Skill 自改进：与当前定位关联弱，且 `RunScriptTool` 含命令执行、带来新的执行风险面。
- Heartbeat：与检索问答型定位关联弱。

## 3. 审批门嵌入现有执行链路（深入方案）

### 3.1 现状执行链路（已核实）

LLM 驱动的工具执行路径为：

```
ReactAgent → ToolCallbackProvider(=AggregatedToolCallbackProvider)
  → createToolCallback(AgentTool) 包装为 Spring AI ToolCallback
  → ToolCallback.call(input) 内: 解析 JSON 参数 → agentTool.execute(params)   ← 工具真正执行点
```

关键事实：`AgentToolRegistry.executeTool()`（带审计）**并不走 ReactAgent 路径**——ReactAgent 通过 `AggregatedToolCallbackProvider.createToolCallback` 的 `call()` **直接调用 `agentTool.execute(params)`**。因此审批/审计拦截点应放在 `AggregatedToolCallbackProvider.call()` 内。

### 3.2 推荐嵌入方案

在 `AggregatedToolCallbackProvider.createToolCallback(...).call(input)` 内、调用 `agentTool.execute(params)` 之前增加审批门：

1. **判断是否需要审批**：新增 `ApprovalService.gate(toolName, argsJson, ...)`：
   - 默认放行（`agentTool.requiresApproval()` 为 false 且无参数级策略匹配）；
   - 对声明 `requiresApproval=true` 或有 `ToolArgsApprovalPolicy` 匹配的工具置 PENDING。
2. **PENDING 语义**：工具调用**暂停并上抛**（返回给 LLM 一条"该操作待人工审批"的系统消息，作为 OBSERVATION），由 Controller 暴露 `POST /api/agent/approval/{id}/approve|deny` 由人工裁决。
3. **approve 后重放**：`approve()` 可重放执行 `agentTool.execute(args)` 并回填结果；为满足「LLM 正确消费结果」，可将审批完成的工具结果写入审批上下文，再以工具消息补送 LLM（ReactAgent 黑盒下需评估回填机制——见风险）。
4. **审计**：沿用 `recordToolAudit` 落一条"审批门命中"审计。
5. **配置开关**：`rag.agent.approval.enabled`（默认 false，避免改变既有 ReactAgent 行为）；`auto-approve` 供测试/演示。

### 3.3 高风险工具的审批策略

| 工具 | 默认行为 | 建议 |
|------|---------|------|
| `DatabaseQueryTool`（SQL 执行） | 直接执行 | **纳入审批**；白名单 DQL 放行、DDL/DML 待审批 |
| `ExecuteTool`（命令执行） | 直接执行 | **纳入审批** + 叠加沙箱/权限校验 |
| `DownloadTool` | 直接执行 | 按需配置 |
| `ApiDocTool`/`CodeSearchTool` | 只读 | 放行 |

### 3.4 风险与注意事项

- **黑盒回填**：ReactAgent 黑盒下，审批通过后无法直接替换/重放 LLM 的工具调用节点，需评估「以工具结果消息回补」是否被现有 ReactAgent 正确消费；若不支持，审批工具的结果需通过重新发一轮 user 消息携带。
- **阻塞超时**：审批为异步人工动作，需设计超时/过期（PENDING 长时间未裁决应失效），避免工具调用悬挂。
- **安全叠加**：审批门是"人肉确认"，不能替代 `SqlSecurityValidator`/命令白名单等硬校验——两者必须共存（对应项目铁则：不绕过安全校验）。
- **多租户**：审批归属需绑定 `TenantContext`/`userId`，与现有审计/租户上下文一致。

## 4. 推荐落地优先级

| 优先级 | 功能 | 分层 | 改动量 | 核心收益 |
|--------|------|------|--------|----------|
| **P0** | 审批门 Approval | B | 中 | 保护高风险工具，人工兜底 |
| **P0** | Mock LLM 离线模式 | A | 低 | 离线/CI/演示全链路，成本最低 |
| P1 | 轻量工具 Trace | B | 低-中 | 工具级可观测 |
| P1 | 事件溯源审计 | B | 中 | 血缘可重建 |
| P2 | 完整 Orchestrator Trace | B | 高 | 黑盒→透明 |
| 跳过 | Persona / Skill / Heartbeat / 多厂商 | — | — | 已有替代/关联弱/新风险面 |

**推荐最小高价值组合** = 审批门 + Mock 离线模式（两者独立、低耦合、直接增强现有 Agent 与测试体系）。