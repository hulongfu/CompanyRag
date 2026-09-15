# 人工介入（Human-in-the-loop）提示式设计

> 日期：2026-09-14
> 类型：设计规格（Spec）
> 状态：待用户审阅
> 前置决策：**方案②（提示式）**——对「敏感操作 / 高消耗操作」在执行前附加**非阻塞的准入提示**，不真正挂起等待人工审批。方案①（强制阻塞审批）因会破坏现有同步 chat 模型（`future.get(5min)` + AbortPolicy）而**暂缓**，本 spec 只做提示式。
> 修订说明：明确与 nl2sql **同改 `DatabaseQueryTool`、阶段 2 合并实现**；`warning` 与 nl2sql 的 `SqlSchemaValidator` 共用一套结构化 `ToolResult`（内部载体）封装；并修复 🟡2 透传冲突——`AgentResult` 只有 `answer`/`toolContext` 两个 String 字段，无法同时承载 reflection 的「检索上下文」与 human 的「warning[]」。**不再复用 `toolContext` 字符串传 warning**：warning 走独立载体（内部 `AgentExecutionResult` / 新增 `AgentResult.warnings` 字段）到前端；若阶段 0 走降级（B），warning 改回文本内嵌转发。

## 1. 目标

在不改变同步 chat 主模型的前提下，对高风险工具调用（如命中敏感列/写操作/高消耗查询）增加**人工可见的准入提醒**，让 LLM 在生成最终回答时向用户显式标注操作风险，供用户知情决策。不阻塞、不改变返回结构，只做增量提示。

**约束：**
- **不动**同步 `CompletableFuture.get(5min)` + AbortPolicy 主模型（human-node 方案①的强制挂起与此根本冲突，故不在本 spec 范围）。
- 不改变 `ChatController` 同步返回契约、`AgentResult` 结构。
- 不启用 graphs 的 human-INterrupt 能力（依赖图断点恢复，当前 `ReactAgent`/单链超时模型不支持）。
- 提示式影响面最小：只改高风险工具的返回内容与 LLM 提示。

## 2. 现状回顾

- `ChatController.chat` → `RagAgentService.processWithHistory` → `callAgentWithTimeout`（子线程 `CompletableFuture`，`future.get(5min)`，队列满 AbortPolicy）。
- `DatabaseQueryTool` 命中敏感列时已有**脱敏**（`SENSITIVE_COLUMNS`），但无"此操作涉及敏感数据"的显式人工提示。
- 无任何人工介入/审批机制。

## 3. 架构设计

**核心思路：** 在工具层识别高风险操作，返回结果中附带结构化"准入提示"，LLM 据此在回答中向用户警示；不应答层面变动。

### 3.1 高风险操作识别（提示式，非阻塞）

| 高风险类型 | 识别点 Flash | 提示策略 |
|---|---|---|
| 敏感列访问 | `DatabaseQueryTool` 命中 `SENSITIVE_COLUMNS` | 返回带 `sensitive=true` 的数据标注，LLM 输出时加警示前缀 |
| 写操作 / 非只读 | SQL/工具含 DML（INSERT/UPDATE/DELETE）或非 SELECT | 返回 `requires-approval=true` 提示 |
| 高消耗查询 | 预估大结果集 / 触发 LIMIT 上限告警 | 返回"结果可能不完整/注意成本"提示 |
| 外部副作用 | 调外部服务（HTTP/邮件等工具，若存在） | 返回副作用说明提示 |

### 3.2 提示消费者（提示式只做"告知"）

- LLM 在生成回答时，将工具返回的 `warning` 附加给用户（如"⚠️ 该查询涉及敏感字段，以下结果已脱敏"）。
- **不挂起**、不校验用户是否确认，仅提示。
- **结构化透传（不依赖 LLM 转述）**：`warning[]` 除进 LLM 文本外，还经**独立载体**（新增 `AgentResult.warnings` 字段，或由阶段 0 引入的内部 `AgentExecutionResult` 承载）透传到前端高亮。即使 LLM 忽略提示，前端仍能展示结构化 warning，杜绝"提示被忽略即完全不可见"。**不复用 `toolContext` 字符串**（🟡2：它已让位给 reflection 的检索上下文，且 String 无法结构化承载）——不得把 JSON 序列化塞进单 String。若阶段 0 走降级（toolContext/上游未打通富载体），则 warning 改**文本内嵌转发**，能力降级但不破坏主链路。

## 4. 数据流

1. LLM 调用高风险工具 ⇒ 工具执行前判定命中高风险类型。
2. 工具仍执行（或按既有脱敏/LIMIT 规则执行），返回结果附带 `warning[]` 条目（等级 + 说明）。
3. LLM 参考 warning，在最终回答中加入人工可见的风险提示措辞。
4. `warning[]` 经**独立载体**（新增 `AgentResult.warnings` 字段 / 阶段 0 内部 `AgentExecutionResult`）透传到前端供非文本高亮（**不复用 `toolContext` 字符串**，🟡2）。
5. `ChatController` 主链路不变，回答文本内已含提示；`AgentResult` 回答/上下文字段结构不变（仅新增可选 `warnings`）。

## 5. 与方案①（强制审批）的取舍

| 维度 | 本 spec（方案②提示式） | 方案①（强制阻塞审批） |
|---|---|---|
| 是否挂起 | 否，非阻塞 | 是，等待人工 |
| 对同步模型影响 | 无 | 破坏（需改同步/恢复）
| 返回结构 | 不变 | 需改（增加 pending/恢复态 |
| 安全强度 | 弱（仅可见提醒） | 强（操作前强制拦截） |
| 改动量 | ~1-2 文件 / ~80 行 | ~8 文件 / ~400 行 |
| 本阶段选择 | **★ 采用** | 暂缓，需求明确后再评估 |

> 说明：若后续确实需要"敏感操作强制人工确认"，应在 spec 层面单独设计一个新的**异步审批会话模型**（新增审批表 + Controller + 前端交互），而非在现有同步 chat 上打补丁。当前方案②作为轻量首步。

## 6. 测试策略

- **工具单测**：命中敏感列/写操作/高消耗三类，验证返回含对应 `warning` 标注。
- **无副作用回归**：未命中高风险时返回结构与现状一致（无 warning）。
- **回答提示**：mock LLM 依据 warning 措辞，验证回答含风险提示（可选）。
- 验证命令采用最窄范围：`DatabaseQueryTool` 相关单测。

## 7. 改动清单

- **修改**：`company-rag-agent/.../tool/DatabaseQueryTool.java`（命中高风险分类时返回 `warning[]`；敏感列场景复用 `SENSITIVE_COLUMNS`）。**与 nl2sql 的 `SqlSchemaValidator` 阶段 2 合并实现于同一文件。**
- **新增（共用）**：`ToolResult` 结构化封装（`data / error? / warning[]`），human 的 `warning` 与 nl2sql 缺失清单错误共用；`WarningItem`/`WarningType` 存储于该封装内。**不可碎片化**为两套返回结构。
- **可选扩展**：`warning[]` 经独立载体（`AgentResult.warnings` / 阶段 0 内部 `AgentExecutionResult`）透传到前端高亮，需阶段 0 前置打通；`AgentResult` 仅在需要时**新增可选 `warnings` 字段**，不复用 `toolContext`（🟡2）。
- **不动**：`ChatController`、`RagAgentService` 同步模型、`AgentResult` 契约、graphs human interrupt 能力。

## 8. 风险与观察项

- **安全强度有限**：提示式不强制拦截，敏感数据仍会被返回（仅脱敏+警示）。真正强管控需走方案①（异步审批模型），留给后续演进。
- **LLM 是否如实转述**：提示依赖 LLM 把 warning 放进回答，存在被忽略可能；可后续在工具选择/回答约束 prompt 中强化。
- **不误伤正常操作**：高风险判定需精确（如 SELECT 不判写操作），避免对普通查询反复加警示干扰体验。
- **前端展示**：当前仅文本注内嵌提示；结构化 `warning` 字段可经独立载体（`AgentResult.warnings` / 阶段 0 `AgentExecutionResult`）导出供前端高亮，属可选配合点，**不复用 `toolContext` 字符串**。
- **同文件冲突（编排）**：与 nl2sql 同改 `DatabaseQueryTool` 与共用 `ToolResult`（内部载体），必须**阶段 2 合并实现**，避免两处独立改动冲突与返回格式碎片化（见编排总览）。
- **阶段 0 前置**：`warning[]` 若需非文本透传到前端，依赖阶段 0 引入的富载体（`AgentExecutionResult`/`AgentResult.warnings`）；在此之前 `warning` 仅靠 LLM 文本转述，能力降级但不破坏主链路。
- **不误伤正常操作**：高风险判定需精确（如 SELECT 不判写操作），避免对普通查询反复加警示干扰体验。