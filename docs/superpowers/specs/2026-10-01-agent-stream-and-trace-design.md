# Agent 流式输出与执行轨迹设计（Agent Stream & Trace）v1.1

> 日期：2026-10-01
> 类型：设计方案（Design Spec）
> 状态：**已冻结**（四段设计逐段批准；两项裁决已按助手推荐定案：①§3.5 客户端在 `DONE` 前断开则不落库不评估，宁缺不残；②§5-R2 经字节码核查降为低风险，改以 §4.3 防回归断言兜底）。本文件为实现依据。
> v1.1 修订要点：①纠正致命实现错误 —— `DONE` 不能用 `doFinally` 组装；②发现直接 `return Flux` + `subscribeOn` 会使统一响应降级（I5）失效，改用 `Sinks` + 方法体内提交；③连带记录该模型的取消副作用及处置；④`rag_session` 落库列名更正为 `context`；⑤明确 `DONE.answer` 来源与污染红线；⑥订阅前失败沿用项目 HTTP 200 + `R.code` 约定，不自行改 HTTP 语义；⑦经字节码核查（§3.3.1）确认 `stream()` 在当前配置下全程同步同线程，R2 由「高风险、合并前须验证」降为「低风险、仅需防回归断言」
> 范围：为 Agent 主链路补上「边生成边推送」的流式能力与「推理段/工具段/技能段」分层执行轨迹，以 SSE 端点对外暴露；不引入新框架，不改动现有阻塞端点。
> 前置结论：本方案**不引入 AgentScope**。选型评估见 §0，结论是所需能力底层已具备，缺的只是上层消费。

---

## 0. 选型前置结论：不整合 AgentScope Java

用户最初的问题是「有没有必要把 Spring AI Alibaba 生态里的 AgentScope 整合进来」。核查结果：

**事实核查（均已实读）**
- `spring-ai-alibaba-examples` 全库检索 `agentscope`，唯一命中 `spring-ai-alibaba-sandbox-example`，引用 `io.agentscope.runtime.sandbox.*`，经 `com.alibaba.cloud.ai:spring-ai-alibaba-sandbox-tool` 引入。即在该示例仓库中，AgentScope 仅以「Runtime 沙箱」形态出现。
- AgentScope Java 本体（`agentscope-ai/agentscope-java`，v2.0.0 GA 于 2026-07，最新 2.0.3，JDK 17+，Apache-2.0）是**独立框架**，自带 model 抽象 / memory / session store / skill registry / sandbox。Maven Central `io/agentscope/` 下的 starter 仅覆盖模型接入（dashscope/openai/anthropic/gemini/ollama）、A2A、AG-UI、状态存储（mysql/postgresql/oss/cos），**无任何 spring-ai 桥接模块**。

**判定：整合 = 替换，不是增强。** 项目已由 Spring AI + spring-ai-alibaba 侧供给 model / memory / skill registry / agent 引擎四样，AgentScope 与之是平行竞争关系，混用会出现两套 ChatModel、两套记忆、两套技能注册表。

**能力对照（AgentScope 卖点 vs 项目现状）**

| AgentScope 卖点 | 项目现状 | 是否真缺口 |
|---|---|---|
| 三态权限门 + HITL | `approve/` 包已自建（状态机 + Mapper + 策略） | 否 |
| 多租户隔离 | Schema 隔离 + RLS + `TenantContext`（铁律 R2） | 否 |
| OS 沙箱 | `ExecuteTool.java:31-34` 明确论证「无需 OS 级沙箱即可闭环」 | 否，主动否决过 |
| 分层记忆 | memory-rework 已落地（`2026-09-26-memory-rework`） | 否 |
| 技能仓库 | `FileSystemSkillRegistry` + `SkillsAgentHook` 已用 | 否（仅缺自进化） |
| **流式事件流** | `StreamingAgentExecutor.java:23` 称「ReactAgent 不支持流式」 | **是 —— 本方案处理** |
| **透明运行轨迹** | hermes 评估报告 §2.2：ReactAgent 黑盒，拿不到 THOUGHT | **是 —— 本方案处理** |
| 多智能体编排 | 单 Agent + 工具/技能路由 | 当前定位不需要（YAGNI） |
| 分布式会话恢复 | 单副本部署，无此需求 | 当前定位不需要 |

**决定性发现（推翻"需要换引擎"的前提）**：`ReactAgent` 1.1.2.0 本身确实只有 8 个 `call()` 重载（`String`/`UserMessage`/`List<Message>`/`Map` × 带不带 `RunnableConfig`）、无 `stream()`，但它暴露 `getCompiledGraph()`，而 `CompiledGraph` 提供：

```java
Flux<NodeOutput> stream(Map<String,Object>, RunnableConfig)
Flux<GraphResponse<NodeOutput>> graphResponseStream(...)
Flux<NodeOutput> streamFromInitialNode(OverAllState, RunnableConfig)
```

且 `StreamingOutput extends NodeOutput` 提供 `chunk()` / `message()` / `getOriginData()` / `getOutputType()`；`OutputType` 枚举 8 个值：`AGENT_MODEL_STREAMING` / `AGENT_MODEL_FINISHED` / `AGENT_TOOL_STREAMING` / `AGENT_TOOL_FINISHED` / `AGENT_HOOK_STREAMING` / `AGENT_HOOK_FINISHED` / `GRAPH_NODE_STREAMING` / `GRAPH_NODE_FINISHED`。

即：**模型 token 增量 + 工具调用起止 + 技能钩子事件，ReactAgent 底层本来就在发**，只是 `StreamingAgentExecutor` 用了阻塞 `call()` 全部吞掉。`StreamingAgentExecutor.java:23` 那句「真正的流式支持需要在 Spring AI Alibaba 层面实现」—— 实现已在，缺上层消费。这同时补掉 hermes 报告 §2.2 的「拿不到 THOUGHT」：`AGENT_MODEL_*` 即推理段，`AGENT_TOOL_*` 即工具段，天然分层。

**曾评估并被否决的替代方案**
- 自写 ReAct 循环（`ChatClient.stream()` + 手动跑 `ToolCallback`）：即 hermes 报告 §2.2 标为「完整版、改动大、动 Agent 核心」而明确推迟的事项；在 graph 流可用的前提下无理由。
- 只做工具级轨迹不做流式（仅扩展 `ToolCallRecorder` payload）：最便宜，但流式缺口原封不动且仍拿不到 THOUGHT。
- 最小可行性验证先行：用户选择直接采用方案 1。

---

## 1. 目标与非目标

### 1.1 纳入范围

1. `StreamingAgentExecutor` 新增流式执行入口，消费 `reactAgent.getCompiledGraph().stream(...)`。
2. 新增事件映射层：`NodeOutput` / `StreamingOutput` → 项目自有事件类型，屏蔽 graph-core 内部类型不外泄到 web 层。
3. 新增 SSE 端点 `POST /api/chat/stream`，与现有阻塞 `POST /api/chat` **并存**，不替换。
4. 轨迹随最终结果落 `rag_session.context` 列（该列已存在，`sql/init.sql:136`，由 `saveConversation` 的 toolContext 入参写入），使历史会话可回看执行过程。**不新增列、不改表结构。**

### 1.2 明确排除（YAGNI）

- 不引入 AgentScope 任何依赖。
- 不重写 ReAct 循环，不改 `ReactAgent` 构建方式（`AgentConfig.java` 一行不动）。
- 不改变现有阻塞端点的任何行为。
- 不改变在线评估的触发口径（`ragUsed` 过滤 + 落库 Owner）。
- 不做前端页面改造，只交付端点契约。
- 不引入多智能体编排。

### 1.3 必须保持的不变式

| 编号 | 不变式 | 依据 |
|---|---|---|
| I1 | 租户上下文在流式全链路不丢失 | 铁律 R2；`TenantContext` 为自定义 ThreadLocal，`ContextSnapshot` 抓不到 |
| I2 | 审批门继续生效，高危工具不因走流式而绕过 | `AggregatedToolCallbackProvider` 拦截点 |
| I3 | 在线评估 `ragUsed` 判定与落库 Owner（`ChatController`）不变 | `2026-09-14-orchestration.md` §4 |
| I4 | SSE 已开始写出后不得抛异常到 `GlobalExceptionHandler` | 铁律 R4 的例外情形，见 §3.2 |
| I5 | 池拒绝/熔断打开/鉴权失败等**建流前**错误仍返回标准 `R<T>`（不建 SSE；HTTP 语义见 §3.1 的边界说明） | 铁律 R4 |

---

## 2. 组件与数据流

### 2.1 新增组件（全部落在 `company-rag-agent`，新包 `com.company.rag.agent.stream`）

| 组件 | 职责 | 依赖 |
|---|---|---|
| `AgentStreamEventType`（枚举） | `TOOL_START` / `TOOL_END` / `ANSWER_DELTA` / `DONE` / `ERROR`（无「思考增量」，理由见 §5-R1 定论） | 无 |
| `AgentStreamEvent`（record） | 单条事件：`type` + `text` + `toolName` + `durationMs` + `status`。`DONE` 事件额外携带 `AgentResult`（answer / toolContext / ragUsed）供 controller 落库使用；**写出 SSE 帧时 `DONE` 不重复携带 `answer` 全文**（前端已逐帧收到 `ANSWER_DELTA`），避免流量翻倍 | 无 |
| `NodeOutputMapper` | **唯一一处**把 `NodeOutput` 翻译成 `AgentStreamEvent`；识别 `OutputType` 与节点名常量 | graph-core |
| `StreamingAgentExecutor.executeStream(...)` | 新增方法，走 `getCompiledGraph().stream(inputs, cfg)`，返回 `Flux<AgentStreamEvent>`。内部按 §3.1 的 `Sinks` + 池任务模型实现，**池满异常从方法调用本身抛出**（不是订阅时）。原 `execute()` 保持不动 | ReactAgent |

**设计要点**：graph-core 的 `NodeOutput` / `StreamingOutput` / `OutputType` 只在 `NodeOutputMapper` 一个文件内出现。web 层只见 `AgentStreamEvent`。这样将来若真的要换 AgentScope（其事件体系是 31 类 typed event），改动面被压缩在 mapper 一处。

`THINKING_DELTA` 与 `ANSWER_DELTA` 的区分依据：同为 `AGENT_MODEL_STREAMING`，处于 ReAct 循环中间轮次（后续还要调工具）的是思考，末轮的是答案。可用判据与回退分支见 §5-R1，由实现计划任务 0 的实测在写代码前定稿。

> **实现阶段定论（已覆盖上段）**：静态核查 `StreamingOutput.extractChunkFromMessage()` 字节码得到
> `if (msg instanceof AssistantMessage am && !am.hasToolCalls()) return am.getText(); else return null;`
> ——工具轮的增量帧 `chunk()` **恒为 `null`**，思考文本根本不会流到前端，无需也无法区分思考事件，
> 故删除 `THINKING_DELTA`。R1 由「待实测」转为静态定论，详见 §5-R1。

### 2.2 改动组件

- `RagAgentService` 新增 `processWithHistoryStream(history, userMessage)`，复用现有超时线程池参数与上下文快照逻辑，返回 `Flux<AgentStreamEvent>`。
- `ChatController` 新增 `POST /api/chat/stream`（`MediaType.TEXT_EVENT_STREAM_VALUE`），**仅**负责订阅 + 在 `DONE` 时落库与触发在线评估。安全校验段复用现有 `/chat` 的 `X-Tenant-Id` + `SecurityUser` 逻辑（`ChatController.java:94-124`），语义照搬不改写。
- 不动：`AgentConfig`、`ReactAgent` 构建、`AggregatedToolCallbackProvider`、审批门、`ExecuteTool`、`DatabaseQueryTool`。

### 2.3 数据流

```
POST /api/chat/stream  (ChatController)
  ├─ 校验：X-Tenant-Id 非空 + SecurityUser 取 userId（失败 → R.fail，未进 SSE，满足 I5）
  ├─ rag.agent.stream.enabled 关闭 → R.fail(业务码 503)，不建流（HTTP 仍 200，见 §3.1 末段）
  ├─ RagChatMemory.get(sessionId) 读有界历史
  └─ RagAgentService.processWithHistoryStream(...)
        ├─ ContextSnapshot.captureAll() + TenantContext 五字段显式快照
        ├─ 建 Sinks.Many<AgentStreamEvent>
        ├─ agentStreamExecutor.execute(task)  ← 池满在**方法返回前**抛出 → controller 转 R.fail（§3.1）
        │
        │  【池任务线程内，顺序执行】
        ├─ 恢复上下文 → getCompiledGraph().stream(inputs, cfg)
        │     inputs = {"messages": [...]}    cfg = RunnableConfig(threadId = sessionId)
        ├─ map(NodeOutputMapper) → AgentStreamEvent，逐条 sink.tryEmitNext
        │     同时累加 ANSWER_DELTA 到 answer 缓冲区
        ├─ timeout(整体上限) / idle timeout；异常 → errored=true + 发 ERROR
        ├─ 若 !errored：发 DONE（answer 全文 + captureToolContext() + usedTool("searchKnowledgeBase")）
        ├─ sink.tryEmitComplete()
        └─ finally: recorder.clearRecords()；TenantContext.clear()      ← 必须晚于 DONE 发出（§3.5）
        │
        └─ return sink.asFlux()（WebMVC 异步 SSE，RagController.java:24 同款先例）
  → controller 在返回前给 Flux 挂 doOnNext：收到 DONE 时
        ├─ ragSessionService.saveConversation(...)   ← 落库 Owner 不变（I3）
        └─ evalExecutor 异步 evaluateAllPersisted(...)  ← 触发口径不变（I3）
```

**关于落库回调的两点约束**

1. `saveConversation` 的租户参数**必须用 controller 方法内已校验的局部变量**（`verifiedTenantId` / `verifiedUserId` / `request.getSessionId()`），与阻塞链路 `ChatController.java:147-156` 完全一致 —— 该方法本就显式接收 `tenantId/sessionId/userId`，不依赖 ThreadLocal。因此 `doFinally` 里的 `TenantContext.clear()` 与回调的先后顺序**不影响落库正确性**，无需为此推迟清理。
2. 该回调在**下游订阅者所在线程**执行，不保证是池线程（`Sinks.unicast` 在无人订阅时会缓冲元素，Spring 开始订阅后才回放）。故回调内**禁止**读取任何 ThreadLocal（`TenantContext` / `ToolCallRecorder`）；需要的数据必须全部装在 `DONE` 事件里带过来。这也反过来要求 §2.4 的取值必须在池任务内完成。

### 2.4 `toolContext` / `ragUsed` / `answer` 的取值时机与线程归属

`ToolCallRecorder` 用 `ThreadLocal<List<ToolCallRecord>>` 存记录，工具在 graph 节点线程执行。阻塞链路里 `RagAgentService.processWithHistory:152` 在 **controller 线程**调 `recorder.getAndClearRecords()`，取到的实际是空列表 —— 这是 §3.7 记录的既有缺陷。流式实现不得重复。

**做法：把整条流的消费封闭在池任务内部，用顺序代码而非 Reactor 操作符收尾。** 依 §3.1 的 `Sinks` 模型，池任务内同步阻塞消费 graph 流，任务栈从头到尾在同一线程，取值退化为普通顺序语句：

```java
// 池线程内执行（agentStreamExecutor 的 Runnable 主体）
void runStream(Sinks.Many<AgentStreamEvent> sink, ...) {
    try {
        restoreContext(ctxSnapshot, tenantSnapshot);        // I1
        AtomicBoolean errored = new AtomicBoolean();
        StringBuilder answer = new StringBuilder();         // 只累加 ANSWER_DELTA
        graphStream(inputs, cfg)
            .map(nodeOutputMapper::map)                     // NodeOutput → AgentStreamEvent
            .doOnNext(ev -> {
                if (cancelled.get()) throw new CancellationException("client disconnected");  // §3.4
                if (ev.type() == ANSWER_DELTA) answer.append(ev.text());
                sink.tryEmitNext(ev);
            })
            .timeout(...)
            .onErrorResume(e -> { errored.set(true); sink.tryEmitNext(AgentStreamEvent.error(e)); return Flux.empty(); })
            .blockLast();                                   // 阻塞在池线程，不碰 event-loop
        if (!errored.get()) {                               // 错误路径不发 DONE（见下）
            sink.tryEmitNext(buildDoneEvent(answer.toString(),
                    recorder.captureToolContext(),
                    recorder.usedTool("searchKnowledgeBase")));
        }
        sink.tryEmitComplete();
    } catch (CancellationException e) {                     // §3.4 客户端断开：不发 ERROR/DONE
        sink.tryEmitComplete();
    } catch (Exception e) {                                 // 含 blockLast 包装出的运行时异常
        sink.tryEmitNext(AgentStreamEvent.error(e));
        sink.tryEmitComplete();
    } finally {
        recorder.clearRecords();
        TenantContext.clear();                              // 防线程池串扰
    }
}
```

**为什么不用 `doFinally` 组装 `DONE`**：`doFinally` 在上游终止信号**之后**执行，Reactor 已向下游发完 `onComplete`，其后的 `onNext` 会被丢弃 —— `DONE` 永远到不了 controller，落库与在线评估全部失效（I3 被静默破坏），且失败方式无声。用 `Sinks` + 顺序代码后该陷阱不存在；若实现阶段仍选纯 Reactor 管道，则必须改用 `concatWith(Flux.defer(...))` 取值、`doFinally` 只做清理，两者不可互换。

**错误路径不得发 `DONE`**：`onErrorResume` 把异常转成 `ERROR` 后流会正常 `complete`，若无 `errored` 判据就会在 `ERROR` 后紧跟 `DONE`，controller 据 `DONE` 落库即写入空/半截答案，直接违反 §3.5「宁缺不残」。§4.2 的异常用例须断言「有 `ERROR` 且**无** `DONE`」。

**`answer` 全文的来源**：流式链路没有现成的完整答案字符串（阻塞链路靠 `reactAgent.call()` 返回值）。做法是管道内累加 `ANSWER_DELTA` 的 `text`（见上 `answer` 累加器）—— 与阻塞链路「末轮模型输出全文」语义等价。**不得**累加 `THINKING_DELTA`，否则思考过程会污染入库答案与后续会话记忆（`RagChatMemory` 会把它当历史读回）。若 R1 判据最终退化为不区分思考/答案的单一 `DELTA`，则 `answer` 改取 graph 末态 state 中最后一条 `AssistantMessage`（由 mapper 在 `AGENT_MODEL_FINISHED` 时记录），此路径在 R1 验证时一并确定。

> **实现阶段定论（已覆盖上句）**：R1 静态定论后，`ANSWER_DELTA` 只可能来自末轮（工具轮 `chunk()` 恒空），
> 累加语义与「末轮全文」等价，故**累加仍是主口径**；同时 mapper 提供
> `roundFinishedText(NodeOutput)` 取 `AGENT_MODEL_FINISHED` 帧 `message().getText()` 作为兜底 ——
> 仅当累加结果为空而 FINISHED 帧有全文时才采用，避免整条流一帧未发却落库空答案。

若上述防回归断言（§4.3）将来失败，回退方案：改用 `RunnableConfig.context()`（实测存在，返回可变 `Map<String,Object>`，另有 `clearContext()`）承载租户信息；但需先确认 graph 是否把该 context 传到工具的 `ToolContext`，若不传则在 `AggregatedToolCallbackProvider` 包装工具时注入。

---

## 3. 错误处理与并发边界

### 3.1 线程模型（最关键，决定审批门能否存活）

审批门是 `while + Thread.sleep` 阻塞轮询（`ToolApprovalService.java:86-99`）。若 graph 流被调度到 Netty event-loop，一个待审批工具会拖死整个 HTTP 线程组。

```java
// 不用 subscribeOn（原因见本节末「实现约束」）；直接提交到有界池
agentStreamExecutor.execute(() -> runStream(sink, ...));   // 见 §2.4
```

- 新增 `agentStreamExecutor`，参数语义复用 `rag.agent.executor.*`（core/max/queue），拒绝策略 `AbortPolicy`。
- 与现有 `RagAgentService` 超时池**分离**：流式会长时间占用线程（含审批等待），与阻塞调用混池会互相饿死。
- 池拒绝发生在 SSE 头写出之前 → 可正常返回 `R.fail`，满足 I5。**HTTP 状态码的准确边界**：项目 `GlobalExceptionHandler` 的 7 个 handler 中，除 `handleBizException` 外**均带 `@ResponseStatus`**（`IllegalArgumentException`→400、`AuthenticationException`→401、`AccessDeniedException`→403、`RuntimeException`/`Exception`/`MethodArgumentNotValid`/`MaxUploadSizeExceeded`→400 或 500），响应体是 `R<T>` 但 HTTP 状态**不是**恒 200。因此「HTTP 200 + 业务码」这条约定**只适用于方法体内显式 `return R.fail(...)` 的路径**（池拒绝、开关关闭），这类路径不经过异常处理器。而鉴权失败沿用 `chat()` 现有的抛异常语义，实际返回 **HTTP 400**（与阻塞端点行为一致）。流式端点不得自行用 `ResponseEntity` 干预 HTTP 状态。

**实现约束（I5 能否成立的前提）**：项目是 WebMVC 栈（各模块均 `spring-boot-starter-web`，`RagController.java:24` 已有返回 `Flux` 的先例，走 Spring MVC 的 `ReactiveTypeHandler` 异步适配）。若 controller 直接 `return flux` 而流用 `subscribeOn` 延迟到订阅时才提交任务，池拒绝异常发生在 Spring 开始订阅之后，此时响应已确定为 SSE，异常无法再转成 `R.fail` —— I5 落空。

因此**不使用 `subscribeOn`**，改为「方法体内即提交」：service 用 `Sinks.Many<AgentStreamEvent>` 承载输出，在**方法返回之前**直接 `agentStreamExecutor.execute(task)`；池满时 `execute` 当场抛 `RejectedExecutionException`，controller 捕获后 `return R.fail(...)`。只有提交成功才 `return sink.asFlux()`。

```java
Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
try {
    agentStreamExecutor.execute(() -> runStream(sink, ...));   // 池满 → 此处立即抛
} catch (RejectedExecutionException e) {
    return R.fail(503, "系统繁忙，请稍后重试");                  // 尚未返回 Flux，可正常降级
}
return sink.asFlux();                                           // WebMVC 异步 SSE
```

此约束须在实现计划里作为独立步骤验证（断言池满时拿到的是 `R` JSON 而非半开的 SSE 流），否则 I5 只是纸面承诺。

### 3.2 超时与失败降级（两层，语义不同）

| 层 | 配置项 | 触发后行为 |
|---|---|---|
| 整体上限 | 复用 `rag.agent.executor.timeout-minutes`（默认 5min） | 推 `ERROR` 事件（含「请简化问题或减少工具调用」提示）后正常 `complete`，**不抛** |
| 增量间隔 | 新增 `rag.agent.stream.idle-timeout-seconds`（默认 60s）→ `Flux.timeout(...)` | 同上；解决「模型 hang 住但 TCP 连接不断」 |

**铁律 R4 的例外处理**：SSE 已开始写响应后状态码不可更改。故流内所有失败统一降级为 `ERROR` 事件 + 流正常结束，**绝不**让异常穿透到 `GlobalExceptionHandler` —— 否则处理器会往已提交的响应里写 `R<T>` JSON，产生脏帧并让前端解析失败。这是 I4 的全部含义，不是对 R4 的违反。

### 3.3 租户上下文（I1，铁律 R2）

复用 `RagAgentService.java:188-194` 已验证的显式快照模式：

- `ContextSnapshot.captureAll()` 捕获 Observation span + MDC；
- 手动捕获 `TenantContext` 的 schema / tenantId / userId / tenantCode / sessionId 五个字段；
- 在池任务体开头（`runStream` 的 `try` 首行）恢复；
- 在池任务的 `finally` 内 `TenantContext.clear()`。

绝不依赖 controller 线程 ThreadLocal 的跨线程可见性。

#### 3.3.1 线程归属核查（字节码证据，已定论）

流式链路的租户安全完全取决于「工具在哪个线程执行」。对锁定的 `1.1.2.0` 版本做字节码核查，四项结论：

| 核查对象 | 命令要点 | 结论 |
|---|---|---|
| `CompiledGraph` | `javap -c` 找 `Schedulers` / `subscribeOn` / `supplyAsync` / `runAsync` | **无任何线程切换**，仅 `Flux.just/empty/error/flatMap/last`；6 处 `CompletableFuture` 常量引用但无任何异步/阻塞调用 |
| `AgentLlmNode`（模型节点） | 同上 | **零**线程/调度器引用 |
| `AgentToolNode`（工具节点） | 存在 `CompletableFuture.runAsync`，但全部位于 `executeToolCallsParallel`，受字段 `parallelToolExecution` 控制 | Builder 构造器中 `iconst_0 → putfield parallelToolExecution`，**默认 false**；全项目检索 `parallelToolExecution\|wrapSyncToolsAsAsync\|maxParallelTools\|toolExecutor` **零命中** |
| `NodeExecutor` | 存在 `Schedulers.parallel()` / `Schedulers.fromExecutor()` / `Flux.subscribeOn` | 三者**只出现在 `handleParallelGraphFlux`**（处理 `ParallelGraphFlux`），即仅当图内存在并行分支节点时生效；线性 ReAct 图不命中 |

`ParallelNode` / `ConditionalParallelNode` 的 `supplyAsync` 同理，需图内显式并行节点，本项目 `AgentConfig` 未使用。

**结论**：当前配置下 `getCompiledGraph().stream()` 是纯同步 pull，整条链在订阅者线程上顺序执行。本方案的订阅者即 `agentStreamExecutor` 的池线程（§3.1），故 `TenantContext` 与 `ToolCallRecorder` 的 ThreadLocal 全程有效，§2.4 的顺序取值成立。

**残留风险（配置漂移）**：一旦将来开启 `parallelToolExecution(true)` 或加入并行节点，工具会被投递到 `getToolExecutor(config)` 返回的线程池，ThreadLocal 丢失且**不会报错** —— 表现为工具静默读到错误的 schema 或空记录，属跨租户事故。因此必须配 §4.3 的防回归断言，并在 `AgentConfig` 的 `ReactAgent.builder()` 处留注释说明该约束。

### 3.4 客户端断线与取消

**必须显式处理的副作用**：改用 `Sinks` + 池任务内 `blockLast()` 后，下游取消**不会**自动中断池任务 —— 池任务是普通阻塞 `Runnable`，与下游订阅者之间被 `Sinks` 隔开。后果是客户端断开后服务端仍会跑完整个 ReAct 循环：继续消耗 LLM token、继续占用池线程、审批门仍会等待人工审批。

处置（实现计划须含此步）：

```java
sink.asFlux().doOnCancel(() -> cancelled.set(true))   // controller 侧置标志
```
池任务的 `doOnNext` 内每次事件先检查 `cancelled`，为真则抛特定异常终止 `blockLast()`，落入 `catch` 后**不发 `DONE`**、直接 `tryEmitComplete()`（下游已取消，实际无副作用），`finally` 正常清理。这样断线后最多再消耗一个事件的时间即停止。

**清理**：`finally` 统一收尾 `recorder.clearRecords()` + `TenantContext.clear()`。

**sink 缓冲语义**：`Sinks.many().unicast().onBackpressureBuffer()` 会在无订阅者时缓冲元素、订阅后回放。因此 Spring 若已订阅而客户端中途断开，取消之后 buffer 内剩余事件（含 `DONE`）被丢弃 → 落库回调不触发，符合 §3.5「宁缺不残」；若 Spring 从未订阅（连接极早断开），同理不落库。

### 3.5 落库与评估语义（用户已确认的取舍）

`saveConversation` 与在线评估**只在收到 `DONE` 时触发**。客户端在 `DONE` 前断开 → 不落库、不评估。

理由：半截答案写入 `rag_session` 会污染后续会话记忆（`RagChatMemory` 会把它当历史读回），且 faithfulness 评估基于不完整回答，会污染评估统计与回归指标。宁缺不残。

### 3.6 熔断（R3）—— 诚实处理既有缺口，不假装满足

**现状核查**：`@CircuitBreaker` 仅出现在 rag 模块的 `search` / `streamAnswer` / `retrieve` / `rerank`；**Agent 链路的 LLM 调用当前零熔断包装**，与 R3「所有通过 Spring AI 发起的 LLM 调用必须使用 CircuitBreaker 包装」不符。这是既有缺口，不由本方案引入。

本方案的处理：
1. 流式入口用**手动 `CircuitBreaker` 门控**（`circuitBreakerRegistry.circuitBreaker("rag-agent")` + `tryAcquirePermission()`），在返回 Flux 之前同步判定，熔断打开即抛 `CallNotPermittedException` → controller 转 `R.fail`，满足 I5。**不用 `@CircuitBreaker` 注解**，理由见本节末「为什么不能用 `@CircuitBreaker` 注解」。
2. 流内失败在 `onErrorResume` 中显式 `registry.counter("rag.agent.stream.error")` 计数（与现有 `metricsRecorder` 共用 Micrometer `MeterRegistry`），保证可观测（R10）。**流内失败不计入熔断统计**，口径边界见本节末「熔断统计口径」。
3. 把「Agent 链路 LLM 调用缺熔断」写入 §7 已知限制，不静默掩盖、不在本方案内顺手扩大改动面。

**为什么不在此处顺手补全 Agent 链路的熔断**：`ReactAgent` 的 LLM 调用发生在 graph 节点内部，无法用注解式 AOP 包装；要真正满足 R3 需改 `AgentLlmNode` 层或包一层 `ChatModel` 装饰器，属独立议题。

**为什么不能用 `@CircuitBreaker` 注解（字节码核查结论）**：`CircuitBreakerAspect.proceed()` 有两条分派路径 —— 若 `CircuitBreakerAspectExt` 能处理返回类型（Reactor），走 `ReactorCircuitBreakerAspectExt.handle()` → `Flux.transformDeferred(CircuitBreakerOperator.of(cb))`，熔断判定**延迟到订阅时**；否则走 `defaultHandling()` → `cb.executeCheckedSupplier(pjp::proceed)`，判定在**方法调用时**。选哪条由 `ReactorOnClasspathCondition.matches()` 决定，其字节码是 **AND** 判定：`reactor.core.publisher.Flux` **且** `io.github.resilience4j.reactor.AbstractSubscriber` 都要在 classpath。

本项目当前**没有** `resilience4j-reactor` 依赖（`resilience4j-spring-boot3` 不传递它），所以走 `defaultHandling` 的同步路径。但这带来两个不可接受的问题：

1. **配 `fallbackMethod` 会破 I5**：同步抛出的 `CallNotPermittedException` 会被 fallback 捕获并返回 `Flux.just(error)`，controller 拿到的是一个 Flux → 响应仍是 SSE，不是 `R.fail`。
2. **classpath 脆弱**：将来任何人引入 `resilience4j-reactor`（哪怕只为别的目的），分派路径静默切换到订阅时判定 → 熔断打开时变成流内 `ERROR` 事件，I5 无声失效。

**采用方案：手动 `CircuitBreaker` 门控**（不用注解）。在流式入口方法体内、返回 Flux **之前**：

```java
CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("rag-agent");
if (!cb.tryAcquirePermission()) {
    // 熔断打开：建流前同步抛出，controller 转 R.fail（满足 I5）
    throw CallNotPermittedException.createCallNotPermittedException(cb);
}
```

**记账点：`onSuccess` 在方法体内、`return flux` 之前立即调用**，不等流结束。`onError` 只在**建流阶段抛异常**时调用（即 `executeStream` 同步抛出、尚未返回 Flux）：

```java
CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("rag-agent");
if (!cb.tryAcquirePermission()) {
    throw CallNotPermittedException.createCallNotPermittedException(cb); // 熔断打开 → controller 转 R.fail
}
    long start = System.nanoTime();
    try {
        Flux<AgentStreamEvent> flux = streamingAgentExecutor.executeStream(...);
        cb.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS); // 建流成功即记账，立即归还许可
        return flux;
    } catch (RuntimeException e) {
        cb.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e); // 建流失败计入失败率
        throw e;
    }
```

**熔断统计口径（明确边界，勿夸大）**：本熔断器衡量的是 **「建流阶段成败率」** —— 分母是流式请求的建流尝试，分子是建流阶段的同步失败（池满 `RejectedExecutionException`、`executeStream` 内的同步异常等）。**不衡量流内失败率**，理由三条：

1. **机制上做不到**（这是本条口径的硬约束，不是取舍）：§2.4 / 任务 3.2 的 `onErrorResume` 把异常转成 `ERROR` 事件后返回 `Flux.empty()`，流以 `onComplete` **正常终止**。若把 cb 记账挂在 `doOnComplete`/`doOnError`/`doOnCancel` 上，流内失败会**恒走 `doOnComplete` → 被记成 `onSuccess`**，失败率被静默美化，比不统计更糟。
2. **语义上不应该**：流内失败已经优雅降级为 `ERROR` 事件推给前端，用户拿到了可读反馈；且单次模型超时之类的抖动不该放大成 30s 全链路拒绝（`wait-duration-in-open-state: 30s`）。熔断器在这里的职责是**保护资源（池）**，不是统计答案质量。
3. **可观测性已由计数器覆盖**：流内失败走 `rag.agent.stream.error` 独立计数器（R10），与熔断解耦。

> **反过来说，建流阶段必须记 `onError`，否则熔断器形同虚设**：若池满只调 `releasePermission()`（归还许可、不记结果）而不记 `onError`，则该熔断器将**永远收不到任何失败样本 → 失败率恒为 0 → 永不打开**，本节整套门控与实现计划任务 7.5 的熔断开手工验收都无意义。`releasePermission()` 与 `onError()` 都会归还许可，但**只有后者计入失败率**，二者不可互相替代。

**为什么 `onSuccess` 不等流结束**：许可的占用时长 = `tryAcquirePermission()` 到 `onSuccess/onError/releasePermission()` 之间。若挂在流终止时，一条流会挂占许可长达 `timeout-minutes`（默认 5 分钟），HALF_OPEN 的探测名额（`DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN_STATE = 10`）会被几条长挂的流占满，熔断器卡在 HALF_OPEN 无法收敛。建流成功即归还，许可语义与「保护建流资源」一致。

相比注解方案的优势：**只扣一次许可**（「注解 + 手动检查并用」会双扣，HALF_OPEN 下探测数减半、失败统计翻倍失真），且统计口径由我们自己写死、不随 classpath 变化。

### 3.7 既有缺陷记录（本次不修）

`RagAgentService.processWithHistory:152` 在 controller 线程调用 `recorder.getAndClearRecords()`，而工具在池线程执行 → 取到空列表。后果：
- `[AGENT] tools=[...]` 结构化日志恒为空；
- `toolContext` 恒回退成 `MDC.get("traceId")`（`RagAgentService.java:159`）。

这正是 `2026-09-14-orchestration.md` 阶段 0 所述「`execute()` 的 `toolContext` 恒为 null / 实际是 traceId」的根因之一。流式实现从设计上避开（§2.4），**阻塞链路保持原状**，避免本次改动波及在线评估的 faithfulness 判定口径。修它需要独立评估对已有评估数据基线的影响。

### 3.8 灰度开关

`rag.agent.stream.enabled`（默认 `false`）。关闭时 `/api/chat/stream` 返回 `R.fail(503, "流式接口未启用")`，不建流。

**配置落地要求**：沿用 `rag.eval.enabled` 的教训 —— 该开关无 `matchIfMissing`，基段 `application.yml` 缺键会导致 prod/test profile 下端点整体不可用且表现为 404 而非可读错误。本开关必须在**基段 `application.yml` 显式写出**，不依赖 profile 补写；且关闭时应命中端点并返回可读的 `R.fail(503,...)` 业务码（HTTP 仍为 200，与项目统一响应约定一致），而不是因 Bean 未装配而 404。

---

## 4. 测试策略

框架沿用项目规范（`conventions.md:74-78`）：JUnit 5 + Mockito，命名 `{methodName}_{scenario}_{expectedResult}`，核心逻辑覆盖率目标 ≥ 80%。

### 4.1 `NodeOutputMapperTest`（新建；纯单测，价值最高）

| 场景类型 | 用例 | 断言 |
|---|---|---|
| 正常 | `AGENT_MODEL_STREAMING` + 中间轮次节点 | 映射为 `THINKING_DELTA`，`text == chunk()` |
| 正常 | `AGENT_MODEL_STREAMING` + 末轮（工具已执行完） | 映射为 `ANSWER_DELTA` |
| 正常 | `AGENT_TOOL_FINISHED` | 产出 `TOOL_END` 且带 `toolName` 与 `durationMs`，不产文本增量 |
| 边界 | `chunk()` 为 null / 空串 | 返回空映射，不产生噪声帧 |
| 边界 | `OutputType` 为 `AGENT_HOOK_*`（节点名带 `AGENT_HOOK_NAME_PREFIX`） | 识别为技能钩子，**不误判为工具** |
| 异常 | 非 `StreamingOutput` 的裸 `NodeOutput`（`GRAPH_NODE_*`） | 忽略且不抛 |

> **实现阶段的用例修正**：① 「中间轮次 → `THINKING_DELTA`」两条改为「工具轮增量帧 `chunk()` 为 `null`
> → 不产任何事件」，并断言 `chunk()` 本身为 `null` 以锁定框架行为；② `TOOL_END` 的 `durationMs` 断言改为
> **`isNull()`** —— `NodeOutput` 无耗时字段、`TOOL_START` 是否发送未确认，无可靠来源时不编造数值；
> ③ 补 `AGENT_MODEL_FINISHED` 用例：不产事件（其 `chunk()` 是聚合后整轮全文，放行会整段重发），
> 但 `roundFinishedText()` 返回该全文。

### 4.2 `StreamingAgentExecutorStreamTest`（新建）

以 `executeStream(...)` 返回的 `Flux` 为被测对象（mock `reactAgent.getCompiledGraph()` 返回固定 `Flux<NodeOutput>`）：

- 正常：`StepVerifier` 验证事件序列与顺序（`TOOL_START`/`TOOL_END` → `ANSWER_DELTA`* → `DONE`）。
- 正常：`DONE` 事件的 `answer` 等于所有 `ANSWER_DELTA.text` 拼接结果，且**不含**工具轮的任何内容（守 §2.4 的污染红线）。
- 异常：源 `Flux.error(...)` → 产出 `ERROR` 事件、流正常结束、**且断言序列中没有 `DONE`**（守 §2.4 错误路径红线，否则半截答案会入库）。
- 边界：正常完成时 `recorder.clearRecords()` 与 `TenantContext.clear()` 均被调用（池任务 `finally`）。
- 边界（§3.4 取消）：下游取消后 `cancelled` 标志置位、池任务不再发 `DONE`、`finally` 仍完成清理。

### 4.3 `RagAgentServiceStreamTest`（新建）

- 异常：池满（mock `executor.execute` 抛 `RejectedExecutionException`）→ 断言异常从 `processWithHistoryStream(...)` **方法调用本身**抛出，而非从返回的 `Flux` 订阅时抛出。这是 I5 成立的直接证据，也是 §3.1「不用 `subscribeOn`」约束的回归防线。**同时断言 `cb.onError(...)` 被调用且 `cb.releasePermission()` 未被调用** —— 若实现误用 `releasePermission()` 替代 `onError`，许可照样归还、请求照样成功，唯一后果是失败率恒 0、熔断器永不打开，属静默故障（§3.6）。
- 异常：熔断打开（mock `tryAcquirePermission()` 返回 false）→ 断言 `CallNotPermittedException` 从方法调用本身抛出，且 `executeStream` 未被调用。
- 边界：建流成功时 `cb.onSuccess(...)` 在**方法返回之前**已调用（未订阅即记账）。守「许可不被挂占整条流时长」，否则 HALF_OPEN 探测名额会被长流占满、熔断器无法收敛（§3.6）。
- 边界（守 §3.6 统计口径，反向断言）：流内失败时 `cb.onError(...)` **从未被调用**，而 `rag.agent.stream.error` 计数器 +1。因为 `onErrorResume` 会把流变成 `onComplete`，任何挂在流终止回调上的记账都会把流内失败**记成成功**、静默美化失败率。将来若要改回挂回调记账，必须先推翻 §3.6 的口径论证。
- 边界：整体超时命中 → 末事件为 `ERROR` 且含提示语，流正常结束，无 `DONE`。
- 边界（I1）：池任务内 `TenantContext.getSchema()` 等于快照值；任务结束后再次读取为空（防线程池串扰）。
- **防回归（守 §3.3.1 的线程归属前提）**：用一个测试工具回调断言「工具执行线程名 == 池任务线程名」。该断言一旦失败，说明 graph 开始切线程（例如有人打开了 `parallelToolExecution` 或加了并行节点），此时 ThreadLocal 方案静默失效、有跨租户风险，必须改走显式传递而非直接合并。

### 4.4 不做的测试（YAGNI）

- 不引入 MockChatModel 做全链路流式集成测试（hermes 报告 §2.4 列为独立候选项，不绑定本次）。
- 不为 `AgentStreamEvent` / `AgentStreamEventType` 这类纯数据 record 写测试。
- 不写前端 SSE 契约测试。

### 4.5 手工验收（无法自动化，实现计划须列为验收步骤）

1. 开 `rag.agent.stream.enabled=true`，`curl -N` 打 `/api/chat/stream`，肉眼确认 token 逐帧到达，而非最后一次性吐出。
2. 触发需审批的工具（`execute`），确认审批轮询等待期间并发打普通 `/api/chat` 仍正常响应（验证 §3.1 未拖死 HTTP 线程）。
3. 中途 `Ctrl+C` 断开 curl，确认 `rag_session` 未新增行、无评估记录（验证 §3.5）。

---

## 5. 风险登记

| 编号 | 风险 | 等级 | 处置 |
|---|---|---|---|
| R1 | ~~`THINKING_DELTA` 与 `ANSWER_DELTA` 的区分判据不确定~~ **已静态定论（低风险）**：`javap -c -p StreamingOutput` 显示所有携带 `message` 的构造器都用 `extractChunkFromMessage()` 推导 `chunk()`，其字节码为 `instanceof AssistantMessage && !hasToolCalls() → getText()`，否则 `null`。即工具轮增量帧 `chunk()` 恒空 → 思考文本天然不外泄，删除 `THINKING_DELTA` 枚举；`answer` 以累加为主、`roundFinishedText()` 为兜底。原「首选分支 A（按帧 `hasToolCalls()` 判思考）」不成立，因该帧的 `chunk()` 本就是 `null`，判了也无文本可发。该框架内部行为由 `NodeOutputMapperTest` 的两条断言（`chunk()` 为 null / FINISHED 帧 `chunk()` 为整轮全文）锁定，升级依赖会立刻红。**残留待实测项仅剩 §0.4：`AGENT_TOOL_STREAMING` 是否真会发送**（不影响答案正确性，只影响前端有无 `TOOL_START`） | ~~中~~ 低 | 实现阶段先打全量 `OutputType` + `node()` + `agent()` 日志实测一轮再定（实现计划任务 0）。**已实测可得的判据**：`RunnableConfig` 提供 `AGENT_MODEL_NAME="_AGENT_MODEL_"` / `AGENT_TOOL_NAME="_AGENT_TOOL_"` / `AGENT_HOOK_NAME_PREFIX="_AGENT_HOOK_"` 常量，节点类型可直接判定；`StreamingOutput.message()` 在增量帧上返回 `AssistantMessage`，其 `hasToolCalls()` 若能在工具轮提前为真，即可边流边区分（首选分支）。回退：合并为单一 `ANSWER_DELTA`（枚举保留 `THINKING_DELTA` 但本期不产出），`DONE.answer` 改取 `AGENT_MODEL_FINISHED` 时 `message()` 的全文。 |
| R2 | graph 内部切换调度器导致 `TenantContext` / `ToolCallRecorder` 的 ThreadLocal 丢失 | ~~高~~ → **低（已静态定论）** | 见 §3.3.1 的四项字节码核查：项目当前配置下 `stream()` 全程同步、在订阅者线程（即本方案池线程）上执行，ThreadLocal 不丢。**残留风险是配置漂移**：若将来开启 `parallelToolExecution(true)` 或图内加入并行节点，结论立即失效且表现为**静默跨租户**。处置：§4.3 增加一条防回归断言（工具执行线程 == 池任务线程），并在 `AgentConfig` 的 `ReactAgent.builder()` 处加注释说明该约束。 |
| R3 | `getCompiledGraph()` 非 ReactAgent 对外宣称的稳定 API，升级可能变更 | 中 | 版本已在 `company-rag-agent/pom.xml` 锁死 `1.1.2.0`；graph-core 原为纯 BOM 传递（项目 pom 零直接声明），已在父 pom `dependencyManagement` 显式锁定 `${spring-ai-alibaba.version}`，防止将来依赖仲裁变化导致线程模型结论静默失效（spec §3.3.1 依赖 1.1.2.0 的字节码事实）；`NodeOutputMapper` 为唯一耦合点，升级时改动面可控。 |
| R4 | 审批阻塞长时间占用流式池线程，池被占满 | 中 | 池独立（§3.1）+ `AbortPolicy` → `R.fail` 业务码降级；`ApprovalProperties.timeoutSeconds` 已有上限，等待会自行终止。 |
| R5 | 前端未适配，端点上线即无人使用 | 低 | 开关默认关闭，端点存在本身无副作用；本次交付范围明确不含前端。 |

---

## 6. 配置项清单

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `rag.agent.stream.enabled` | `false` | 流式端点总开关；**必须在基段 `application.yml` 显式写出**（§3.8） |
| `rag.agent.stream.idle-timeout-seconds` | `60` | 相邻事件最大间隔，超时推 `ERROR` 并正常结束 |
| `rag.agent.executor.timeout-minutes` | `5`（复用） | 整体上限，复用现有项，不新增 |
| `rag.agent.executor.core-pool-size` / `max-pool-size` / `queue-capacity` | `4` / `8` / `100`（复用） | 流式专用池沿用同一组语义，实现阶段可决定是否拆为 `rag.agent.stream.*` 独立项 |
| `resilience4j.circuitbreaker.instances.rag-agent.*` | **无需新增** | 手动门控 `circuitBreakerRegistry.circuitBreaker("rag-agent")` 会继承项目 `application.yml:179-186` 已有的 `configs.default`：`sliding-window-size: 10`、`minimum-number-of-calls: 5`、`failure-rate-threshold: 50`、`wait-duration-in-open-state: 30s` —— 窗口足够小，低频端点也能正常触发，故**不需要为本方案新增实例配置**。（若照 resilience4j 出厂默认 100/50%，低频端点几乎永不熔断；项目已覆写过，无此问题）<br>注意 `permitted-number-of-calls-in-half-open-state` 未显式配置，走 resilience4j 出厂默认 `DEFAULT_PERMITTED_CALLS_IN_HALF_OPEN_STATE = 10`。双扣许可虽不至于把探测挤死（默认 10 个名额），但仍会让 HALF_OPEN 的实际探测数减半、且失败统计翻倍失真 —— 这是本方案坚持「只扣一次许可」的理由。 |

---

## 7. 已知限制（本方案不解决，明确记录）

1. **Agent 链路 LLM 调用缺熔断包装**，与铁律 R3 不符（既有缺口，非本次引入）。本方案在**流式入口层**加了手动熔断门控，但**统计口径仅为「建流阶段成败率」**，流内失败不进入熔断（口径理由见 §3.6），`ReactAgent` 内部 `AgentLlmNode` 的 LLM 调用仍无保护，阻塞链路同样没有。
2. **阻塞链路 `toolContext` 恒为 traceId** 的既有缺陷不修，见 §3.7。流式链路不受影响。
3. 不提供多智能体编排、跨副本会话恢复、技能自进化 —— 即 AgentScope 的其余卖点，按当前项目定位判定为不需要（§0）。
4. 不改造前端；SSE 端点上线后需另行安排前端接入。

---

## 8. 与其他文档的关系

| 文档 | 关系 |
|---|---|
| `docs/features/2026-09-19-hermes-integration-evaluation.md` §2.2 | 本方案解决其记录的「ReactAgent 黑盒、拿不到 THOUGHT」，且提供了比 hermes 轻量版（仅 TOOL 级）更完整的分层轨迹；未采用其「重写 AgentOrchestrator」完整版路线 |
| `docs/superpowers/specs/2026-09-14-orchestration.md` 阶段 0 | 该文档在「A 捕获 / B 降级」间悬而未决。本方案相当于用 graph 流实现 A 的加强版（不只捕获工具 payload，还拿到推理段），但**只对新流式链路生效**，阻塞链路仍按现状（§3.7） |
| `docs/superpowers/specs/2026-09-14-answer-evaluator-design.md` | 在线评估触发口径不变（I3）；`ragUsed` 判定改由流式链路在池线程内取值（§2.4） |
| `docs/superpowers/specs/2026-09-19-approval-gate-design.md` | 审批门实现与拦截点零改动；本方案仅要求其不在 event-loop 上阻塞（§3.1） |
| `docs/superpowers/specs/2026-09-14-reflection-design.md` | 自省若要用推理段轨迹，可复用本方案的 `NodeOutputMapper` 产物；本方案不主动接入 |
| `docs/superpowers/plans/2026-10-01-agent-stream-and-trace.md` | 本 spec 的实现计划（任务拆分、依赖与配置改动、测试用例清单、手工验收步骤）。R1 的最终判据由该计划任务 0 的前置实测关闭并回写本文件 §5-R1 |
