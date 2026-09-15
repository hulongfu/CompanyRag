# RAG 六工作项 统一编排总览（Orchestration）

> 日期：2026-09-14
> 类型：编排与实施总览（Cross-Spec Orchestration）
> 状态：待用户审阅
> 目的：把 6 份设计方案（`2026-09-14-*.md`）中共享的落点冲突、前置依赖、实施排序、共享能力固化为一处"指挥文档"，避免各方案"各自声称改动封闭"却实际同改同一文件时冲突。
> 修订同步（针对 🟡2/🟡3/🟡4）：① `ToolResult` 明确为工具**内部载体**、对外保持 `String` 签名（🟡3）；② `AgentResult.toolContext` 专供检索上下文（reflection/answer-eval 复用），human `warning` 走独立载体（`AgentResult.warnings` / `AgentExecutionResult`），**不复用 toolContext 字符串**（🟡2，单 String 无法同时承载两者）；③ `TenantContextSnapshot` 只提供 `captureNow()/apply()/clear()`，ETL 用 `apply()` 恢复 + finally `clear()`，无 `restore()`（🟡4）。

## 1. 六方案清单与修订状态

| 方案 | 文件 | 主要风险（修订后） | 阶段 |
|---|---|---|---|
| 阶段 0 前置 | reflection 内嵌（捕获或降级 toolContext） | 若不做，reflection/answer-eval/human 拿不到真实检索上下文（且当前 execute() 的 toolContext 恒为 null） | 0 |
| 回答质量评估独立化 | `2026-09-14-answer-evaluator-design.md` | 纯离线，不碰主链路，可最先落地 | 1 |
| 文档入库健壮性（ETL） | `2026-09-14-rag-etl-hardening-design.md` | 异步每步恢复 TenantContext（拦截器自动续 search_path） | 1 |
| NL2SQL 小升级 | `2026-09-14-nl2sql-tool-hardening-design.md` | 不在工具内调 LLM，改 ReAct 重试 | 2 |
| 人工介入（提示式） | `2026-09-14-human-in-the-loop-design.md` | warning 透传 + 与 nl2sql 合并 | 2 |
| 会话记忆规范化 | `2026-09-14-memory-rework-design.md` | 隔离从 TenantContext 取、唯一落库 Owner | 3 |
| 答后自省修正 | `2026-09-14-reflection-design.md` | 依赖阶段 0 + 阶段 3 主链路稳定 | 3 |

## 2. 实施排序（分阶段、避免互相阻塞）

```
阶段 0（前置，不属任一方案，agent 模块内部封口；捕获或降级二选一）
  ├─ A 捕获（推荐）：接入 ReactAgent 观测钩子/扩展 recorder 捕获检索 chunk/工具结果
  │     → execute() 返回真实 toolContext（当前 L51 硬编码 null）
  │     → callAgentWithTimeout 携带带出 → processWithHistory 用真实 toolContext（而非 MDC.get("traceId")）
  │     · 被依赖方：reflection、answer-evaluator、human-in-the-loop(warning 独立载体) —— 依赖打通后复用
  └─ B 降级：不捕获；reflection 在线仅相关性/遗漏自校，faithfulness 金标准交离线 answer-evaluator，铁律改述
        · A 未落地时，human warning 转文本内嵌转发（🟡2 不复用 toolContext）/ answer-eval 的"复用透传通道"同步降级

阶段 1（互相独立、低风险，可并行）
  ├─ answer-evaluator：纯离线 Evaluator SPI，不碰主链路
  └─ rag-etl：document 模块，注意异步租户隔离（步骤 3.4）

阶段 2（同文件合并实现，必须一起改）
  └─ DatabaseQueryTool 合并改动：
       nl2sql  ── 插入 SqlSchemaValidator(表/列存在性校验) + 失败返回缺失清单(ReAct 重试)
       human   ── 命中高风险返回 warning[]（走独立载体，非 toolContext 字符串）
       · 共用 ToolResult(data/error?/warning[]) 作为工具**内部载体**，对外保持既有 String 签名（🟡3），禁用两套返回格式

阶段 3（主链路收口，串行）
  ├─ memory-rework：定"历史落库 Owner=ChatController"，RagChatMemory 只读不写；主路径手动注入
  └─ reflection：依赖阶段 0 toolContext + 阶段 3 主链路稳定；FaithfulnessChecker 与 answer-evaluator 共享
```

## 3. 共享落点冲突矩阵（修订后）

| 共享文件 / 能力 | 涉及方案 | 冲突处理（修订后） |
|---|---|---|
| `RagAgentService.processWithHistory` | memory、reflection | memory 改历史注入；reflection 在末尾委派自省。**阶段 3 串行**，两者按序改同一方法，避免并行冲突；配灰度开关互相隔离 |
| `RagAgentService.callAgentWithTimeout` / `AgentResult` | reflection（阶段 0）、memory、human | 阶段 0 先定"捕获(A)/降级(B)"：A 补真实 toolContext 透传通道，`toolContext` **专供检索上下文**（reflection、answer-eval 复用）；human `warning` 走独立载体（`AgentResult.warnings` / `AgentExecutionResult`），**不复用 toolContext 字符串**（🟡2，单 String 无法同时承载两者）；B 不捕获、下游降级；memory 不动返回路径 |
| `ChatController.chat`（手拼历史、saveConversation） | memory | 唯一落库 Owner = ChatController；RagChatMemory 只读不写；去手拼改手动注入历史 |
| `DatabaseQueryTool` | human、nl2sql | **阶段 2 合并改**：共用 `SqlSchemaValidator` + `warning`；共用 `ToolResult` |
| 多租户 `TenantContext` / `search_path` 传播 | memory、rag-etl、reflection | 统一复用 `TenantContextSnapshot`（提交时 `captureNow()` 捕获 → worker `apply()` 恢复 → finally `clear()` 清理；🟡4 无 `restore()`）模式；绝不用可伪造 ID 作鉴权输入 |
| 线程池 | rag-etl（新增）、reflection（增量负载） | rag-etl 用独立 `document.pipeline.*` 配置，与 `rag.agent.executor` 分离；reflection 自省走独立短超时子线程 |
| `FaithfulnessChecker` | answer-evaluator、reflection | **共享实现**：在线轻量分支（reflection）与可重评分（answer-eval）复用同一份 faithfulness 判定 |

## 4. 前置依赖（不修则下游方案成立不了）

- **P0：确立阶段 0 的"捕获(A)/降级(B)"**。当前 `execute()` 的 `toolContext` 恒为 `null`（核实），照"透传"改完仍为空。A 打通真实检索上下文供 reflection/answer-eval/human 复用；若走 B，reflection 在线只做相关性自校、faithfulness 金标准交离线 answer-evaluator（铁律改述），human warning 透传降级。
- **P0：明确历史落库唯一 Owner**（ChatController）。memory 防双写。
- **P0：异步每步恢复 `TenantContext`**（rag-etl）。先用 `TenantContextSnapshot.apply()` 恢复再碰 DB，finally `clear()` 清理（🟡4 无 `restore()`），由 `TenantSchemaInterceptor` 自动续 search_path（不手动调已废弃的 `TenantContextHelper`/`resetSqlContext`）。防跨租户写向量库。
- **P0：隔离身份源自 TenantContext，不从可伪造 ID 解析**（memory）。

## 5. 跨方案共享能力与验收铁律

| 能力 | 验收铁律 |
|---|---|
| `toolContext`（阶段 0，A/B 二选一） | A：`AgentResult.toolContext` 含真实检索内容（不再是 traceId），**专供检索上下文**；human `warning` 走独立载体（`AgentResult.warnings` / `AgentExecutionResult`），不得写进 toolContext（🟡2）；B：reflection 在线仅相关性自校、faithfulness 金标准交离线 answer-evaluator（铁律相应改述） |
| 多租户隔离 | 任何场景伪造 ID / 残留 search_path 均不得跨租户读写 |
| 唯一落库 Owner | 同 session 一轮对话 `rag_session` 只新增 1 行 |
| 异步步骤隔离（ETL） | 并发多租户任务下，每步落库命中本租户 schema |
| 安全底座零膨胀 | RLS/脱敏/LIMIT/审计执行段在每次改动后保持通过 |
| faithfulness 复用 | reflection 与 answer-eval 共用同一份 `FaithfulnessChecker`，不各写一套；若阶段 0 走 B，reflection 在线不做 faithfulness，金标准全部归 answer-eval |

## 6. 与其他文档关系

- 每份方案内已内嵌"统一编排 / 阶段"小节，本总览为唯一权威排序。
- references 采用评审 `2026-09-14-design-review.md` 作为修订依据源。