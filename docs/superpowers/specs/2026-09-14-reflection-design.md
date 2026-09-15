# 答后自省修正（Reflection）设计（修订版）

> 日期：2026-09-14
> 类型：设计规格（Spec）
> 状态：待用户审阅（已根据 2026-09-14-design-review 修订）
> 前置决策：在 Agent 首次回答后追加一次「自省→修正」LLM 调用，提升回答质量；作为低成本高价值项先行。
> 修订说明：修复评审指出 🔴1（`AgentResult.toolContext` 前提错误）并加入阶段 0 前置修复，界定超时叠加，明确与 answer-evaluator 的 faithfulness 分工。

## 1. 目标

在 `RagAgentService` 产出回答后，追加一次自省：让 LLM 结合原始问题与检索上下文，评估首答是否存在幻觉、遗漏、事实错误，并输出修正版本或"无需修正"。最终返回修正后文本。

**约束：**
- **不改变**工具调用编排、会话历史、存储链路；只作用于最终回答文本。
- 自省 LLM 调用必须走现有熔断保护（Resilience4j）与租户上下文传播机制。
- 自省失败或无修正价值时**回退原答**，保证不劣化回答。
- 自省作为**独立短超时受控调用**，不挤占 `AGENT_TIMEOUT_MINUTES` 主预算；整体 chat SLA 超界风险由调用方兜底。

## 2. 现状回顾（含阶段 0 前置问题）

### 2.1 当前 `toolContext` 并不承载检索上下文（🔴 前提问题）

- `AgentResult`（`agent/service/AgentResult.java`）字段：
  ```
  private String answer;        // 回答文本
  private String toolContext;   // 工具上下文（当前实际存 traceId 字符串）
  ```
- `RagAgentService.processWithHistory`（L155）：`return new AgentResult(response, MDC.get("traceId"));`——第二个参数 `toolContext` **当前传入的是 traceId 字符串**，并非检索上下文。
- `callAgentWithTimeout` 内部 `streamingAgentExecutor.execute()` 返回的 `AgentResult` 只取了 `getAnswer()`（L211-212 `new AssistantMessage(result.getAnswer())`），把其 `toolContext` **丢弃**了。
- `StreamingAgentExecutor.execute()` **L51 `return new AgentResult(content, null)`——`toolContext` 硬编码 `null`**；ReAct 运行中的工具观测（含检索 chunk / 工具结果）从未被任何对象捕获，现有 recorder 只记工具名/耗时、不含 payload。
- 全仓无任何代码向 `AgentResult.toolContext` 写入真实工具/检索上下文。

### 2.2 当前主流程

- `processWithHistory` 通过 `callAgentWithTimeout(messages)` 得到 `AssistantMessage`，取其 `text` 作为最终回答。
- `callAgentWithTimeout`：子线程传播 `ContextSnapshot` + 手动 `TenantContext` 五字段，`finally TenantContext.clear()`，`future.get(5min)`。
- `ChatController.chat` 把返回文本落库（`saveConversation`）。

## 3. 架构设计

### 3.0 阶段 0 前置：捕获检索上下文 或 降级自省能力（P0，reflection 前提）

**事实核实：** `StreamingAgentExecutor.execute()`（L51 `return new AgentResult(content, null)`）`toolContext` 硬编码 `null`；ReAct 运行中工具观测未被任何对象持有 payload，现有 recorder 只记工具名/耗时。故"透传富工具上下文"这一前提当前**不存在**——照原写法改完 `toolContext` 仍是 `null`。阶段 0 必须在"捕获"与"降级"**二选一**：

**方案 A（捕获，推荐，真上下文）：** 先真正捕获检索 chunk/工具结果，再打通透传链路。
1. 改动点：
   - `StreamingAgentExecutor`：接入 ReactAgent 观测钩子 / 扩展 recorder，把检索 chunk/工具结果摘要写入 `execute()` 返回的 `AgentResult.toolContext`（当前硬编码 `null`）。
   - `callAgentWithTimeout`：用新内部载体（如 `AgentExecutionResult(answer, toolContext)`）带出 toolContext，不再仅构造 `AssistantMessage` 丢弃它。
   - `processWithHistory`：用真实 `toolContext` 构造 `AgentResult`，而非 `MDC.get("traceId")`。
2. 前提：ReactAgent 观测/recorder 能产出 payload 的能力落地。**若观测能力不具备，则 A 不可行，落 B。**

**方案 B（降级，零捕获成本）：** 暂不捕获真实上下文，明确 reflection 在线**只做相关性/遗漏的轻量自校**，**faithfulness 金标准交给 answer-evaluator 离线做**；`toolContext` 为空时自省**不判 faithfulness**（把 §4 的 fallback 从"回退"收紧为"既定策略"，而非写错后的兜底）。

> 编排：阶段 0 走 A 时，透传通道可复用于 human 的 `warning[]` / answer-evaluator 离线评估；走 B 时这些下游依赖同步降级。改动封闭于 `agent` 模块（`RagAgentService` / `StreamingAgentExecutor` / `AgentResult`），不动 Controller 与存储。

### 3.1 核心组件

| 组件 | 职责 | 依赖 |
|---|---|---|
| `ReflectionService` | 输入问题 + 检索上下文 + 首答，经自省 prompt 判断是否需要修正，返回修正后文本与是否修正标记 | ChatModel、Resilience4j `CircuitBreaker`/`Retry` |
| `ReflectionResult` | 数据结构：reflectedText / originalText / refined(boolean) / note | —（纯数据） |
| 自省调用器（内嵌） | 复用阶段 0 的上下文传播与超时模式，独立受控调用 | — |

### 3.2 接入形态（推荐：独立封装，`processWithHistory` 末尾委派）

- `RagAgentService` 注入 `ReflectionService`，在拿到已修复的 `AgentResult`（含真实 toolContext）后，返回前调用 `reflectionService.reflect(query, toolContext, firstAnswer)`。
- 自省服务**自身**在独立子线程执行并隔离上下文（复用阶段 0 的上下文传播），独立短超时。
- 不挤占主 `AGENT_TIMEOUT_MINUTES`：自省设独立 `reflection.timeout-ms`（建议 30–60s），独立于 Agent 5min。

### 3.3 自省流程

```
processWithHistory(history, userMsg)
  → AgentExecutionResult = callAgentWithTimeout(messages)   // 阶段0 后携带真实 toolContext
  → firstAnswer = result.answer
  → refined = ReflectionService.reflect(query, result.toolContext, firstAnswer)
        // 独立受控子线程 + 熔断 + 独立短超时(30-60s)
        // prompt：核对首答是否忠实于上下文、是否有遗漏/事实错误（轻量在线自校）
        // 输出：JSON {refined: boolean, revisedText?: string, note: string}
  → 若 refined=true 用 revisedText；否则用 firstAnswer
  → 返回最终文本（落库即修正后文本）
```

## 4. 上下文来源

- 阶段 0 走**方案 A** 后，`AgentResult.toolContext` 含检索 chunk / 工具结果摘要，作为忠实度（faithfulness）参照。
- 阶段 0 走**方案 B**（未捕获）或上下文为空 → 自省**只做相关性/遗漏检查**，**不判 faithfulness**；faithfulness 金标准统一由 answer-evaluator 离线承担（见 §5 分工）。

## 5. 与 answer-evaluator 的 faithful分工（🟡 明确分工，复用实现）

| 维度 | reflection（本 spec，在线轻量） | answer-evaluator（离线金标准） |
|---|---|---|
| 时机 | 在线每次回答后 | 离线/批量 |
| 成本 | 低，短超时 + 熔断，失败回退原答 | 较高，可重、可报告 |
| 用途 | 即时修正被返回的回答 | 质检报告、反馈信号 |
| faithfulness | 只要"有无明显幻觉"的二元/轻量判断 | 可重粒度评分 |
| 实现 | 抽取**共享的 faithfulness 判定 prompt/工具**，两侧复用，不各写一套 | 同左 |

> 落点：`ReflectionService` 与 `AnswerEvaluationService`（answer-evaluator spec）共用一份 faithfulness 判定实现（如 `FaithfulnessChecker`），reflection 用其轻量分支。

## 6. 错误处理与降级

| 场景 | 策略 |
|---|---|
| 自省 LLM 调用超时/熔断/限流 | 放弃自省，返回 `firstAnswer`（原答），不阻塞主链路 |
| 自省返回 `refined=false` | 直接返回原答（保持语义与成本） |
| 自省返回 malformed JSON | 解析失败按 `refined=false` 处理，回退原答 |
| 阶段 0 未完成 | reflection 开关不生效（依赖前置），spec 与 plan 必须串行执行；阶段 0 最小化落地可选 **方案 B**（仅相关性自校）先行 |

**成本控制：** 自省是二次 LLM 调用，token/延迟约翻倍；熔断 + 独立短超时 + `refined=false` 短路控制；配置开关 `rag.reflection.enabled=false` 默认关闭，灰度开启。

**记忆一致性（🟡）：** reflection 修正仅作用于"本次返回文本"；落库存"首答 + 修正备注/revisedText"以便追溯，避免多轮后无法判断哪句是修正结果。不破坏现有 `saveConversation` 落库主链路。

## 7. 测试策略

- **阶段 0 前置测试**：方案 A——验证观测捕获的检索 payload 进入 `execute()` 返回的 `AgentResult.toolContext`、并经 `callAgentWithTimeout` 透传到 `processWithHistory`，不再是 traceId；方案 B——验证 toolContext 为空时自省**只做相关性/遗漏、不判 faithfulness**（faithfulness 交 answer-evaluator）。
- **单测**：`ReflectionService` mock ChatModel，验证 `refined=true/false`、超时回退、熔断开、malformed JSON 各分支。
- **集成**：`processWithHistory` 注入自省后，验证返回修正版；禁用开关时确认原答不变、`toolContext` 透传不受影响。
- **上下文/租户隔离回归**：自省子线程执行后 `TenantContext` 正确清理，无泄漏污染。
- 验证命令采用最窄范围：`company-rag-agent` 模块相关测试类。

## 8. 改动清单

- **阶段 0（前置，agent 模块，二选一）**：
  - **方案 A（捕获）**：修改 `StreamingAgentExecutor`（接入 ReactAgent 观测钩子 / 扩展 recorder 捕获检索 chunk/工具结果，填入 `execute()` 返回的 `AgentResult.toolContext`，当前硬编码 null）、`callAgentWithTimeout`（带出 toolContext）、`processWithHistory`（用真实 toolContext 构造 AgentResult）；可能引入 `AgentExecutionResult` 载体。
  - **方案 B（降级）**：不捕获真实上下文，reflection 在线仅相关性/遗漏自校，faithfulness 金标准交 answer-evaluator 离线；铁律相应改述。
  - 封口于 agent 模块，不动 Controller 与存储。
- **reflection**：新增 `reflection/ReflectionService.java`、`ReflectionResult.java`；如做 faithfulness 复用，新增 `FaithfulnessChecker`（与 answer-evaluator 共享）；修改 `RagAgentService` 末尾委派。
- **新增配置**：`rag.reflection.enabled(false) / timeout-ms(30000-60000) / max-note-length`；复用既有熔断配置。
- **不动**：`ChatController`（除阶段 0 透传外）、会话存储、工具调用编排、检索链路。

## 9. 风险与观察项

- **阶段 0 是硬前置且需"捕获或降级"二选一**：`execute()` 的 `toolContext` 当前恒为 `null`，照旧透传改完仍为空。方案 A 依赖 ReactAgent 观测能力落地；方案 B 则 reflection 在线仅相关性自校、faithfulness 金标准交离线 answer-evaluator，铁律同步改述。**"先修链路保证 toolContext 一定有值"是做不到的，必须先选 A 或 B。**
- **超时叠加**：最坏总耗时 = Agent 5min + reflection 短超时(30–60s)；整体 >5min 的 wait 由调用方（ChatController/网关）兜底，spec 明确该边界责任。
- **回答变化影响记忆一致性**：已通过"落库首答+修正备注"缓解。
- **faithfulness 不各写一套**：与 answer-evaluator 共用 `FaithfulnessChecker`。
- **不接 graph human/parallel 节点**：纯 LLM 自省，不引入人机交互（见 human-in-the-loop spec）。