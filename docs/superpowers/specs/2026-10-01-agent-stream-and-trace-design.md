# Agent 流式输出与执行轨迹设计（Agent Stream & Trace）v1.1

> 日期：2026-10-01
> 类型：设计方案（Design Spec）
> 状态：设计已获用户逐段批准（§1 范围、§2 组件与数据流、§3 错误处理与边界、§4 测试策略 四段分别确认通过）；v1.1 为自查修订，未改变已批准的范围与非目标，仅纠正实现机制与事实错误（见下）
> v1.1 修订要点：①纠正致命实现错误 —— `DONE` 不能用 `doFinally` 组装；②发现直接 `return Flux` + `subscribeOn` 会使统一响应降级（I5）失效，改用 `Sinks` + 方法体内提交；③连带记录该模型的取消副作用及处置；④`rag_session` 落库列名更正为 `context`；⑤明确 `DONE.answer` 来源与污染红线；⑥订阅前失败沿用项目 HTTP 200 + `R.code` 约定，不自行改 HTTP 语义
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

**决定性发现（推翻"需要换引擎"的前提）**：`ReactAgent` 1.1.2.0 本身确实只有 6 个 `call()` 重载、无 `stream()`，但它暴露 `getCompiledGraph()`，而 `CompiledGraph` 提供：

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
| I5 | 池拒绝/鉴权失败等**建流前**错误仍返回标准 `R<T>`（HTTP 200 + 业务码，见 §3.1） | 铁律 R4 |

---

## 2. 组件与数据流

### 2.1 新增组件（全部落在 `company-rag-agent`，新包 `com.company.rag.agent.stream`）

| 组件 | 职责 | 依赖 |
|---|---|---|
| `AgentStreamEventType`（枚举） | `THINKING_DELTA` / `TOOL_START` / `TOOL_END` / `ANSWER_DELTA` / `DONE` / `ERROR` | 无 |
| `AgentStreamEvent`（record） | 单条事件：`type` + `text` + `toolName` + `durationMs` + `status`。`DONE` 事件额外携带 `AgentResult`（answer / toolContext / ragUsed）供 controller 落库使用；**写出 SSE 帧时 `DONE` 不重复携带 `answer` 全文**（前端已逐帧收到 `ANSWER_DELTA`），避免流量翻倍 | 无 |
| `NodeOutputMapper` | **唯一一处**把 `NodeOutput` 翻译成 `AgentStreamEvent`；识别 `OutputType` 与节点名常量 | graph-core |
| `StreamingAgentExecutor.executeStream(...)` | 新增方法，走 `getCompiledGraph().stream(inputs, cfg)`，返回 `Flux<AgentStreamEvent>`。内部按 §3.1 的 `Sinks` + 池任务模型实现，**池满异常从方法调用本身抛出**（不是订阅时）。原 `execute()` 保持不动 | ReactAgent |

**设计要点**：graph-core 的 `NodeOutput` / `StreamingOutput` / `OutputType` 只在 `NodeOutputMapper` 一个文件内出现。web 层只见 `AgentStreamEvent`。这样将来若真的要换 AgentScope（其事件体系是 31 类 typed event），改动面被压缩在 mapper 一处。

`THINKING_DELTA` 与 `ANSWER_DELTA` 的区分依据：同为 `AGENT_MODEL_STREAMING`，处于 ReAct 循环中间轮次（后续还要调工具）的是思考，末轮的是答案。判定方式由实现阶段确定，可选判据见 §5-R1。

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

若实测发现 graph 内部切换调度器导致工具不在池任务线程上执行（`captureToolContext()` 取到空），回退方案见 §5-R2。

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
- 池拒绝发生在 SSE 头写出之前 → 可正常返回 `R.fail`，满足 I5。**注意**：项目 `GlobalExceptionHandler` 各方法返回 `R<Void>` 且无 `@ResponseStatus`，即 HTTP 状态恒为 200、错误码走 `R.code` 业务码。流式端点的订阅前失败沿用同一约定（业务码表达「系统繁忙」），不自行改用 `ResponseEntity` 改变 HTTP 状态语义。

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

绝不依赖 controller 线程 ThreadLocal 的跨线程可见性。**风险点**：若 graph 在节点间自行切换调度器，`TenantContext` 会在节点中途丢失，导致工具访问错误 schema —— 这是本方案最高风险项，处置见 §5-R2。

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
1. 流式入口方法加 `@CircuitBreaker(name = "rag-agent", fallbackMethod = ...)`。因 §3.1 已改为「方法体内即提交」，池拒绝等失败在方法调用时同步抛出，注解式 AOP 可正常捕获（`fallbackMethod` 返回类型须与被测方法一致，返回 `Flux`）。
2. 流内失败在 `onErrorResume` 中显式 `registry.counter("rag.agent.stream.error")` 计数（与现有 `metricsRecorder` 共用 Micrometer `MeterRegistry`），保证可观测（R10）。
3. 把「Agent 链路 LLM 调用缺熔断」写入 §7 已知限制，不静默掩盖、不在本方案内顺手扩大改动面。

**为什么不在此处顺手补全 Agent 链路的熔断**：`ReactAgent` 的 LLM 调用发生在 graph 节点内部，无法用注解式 AOP 包装；要真正满足 R3 需改 `AgentLlmNode` 层或包一层 `ChatModel` 装饰器，属独立议题。

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

### 4.2 `StreamingAgentExecutorStreamTest`（新建）

以 `executeStream(...)` 返回的 `Flux` 为被测对象（mock `reactAgent.getCompiledGraph()` 返回固定 `Flux<NodeOutput>`）：

- 正常：`StepVerifier` 验证事件序列与顺序（`THINKING_DELTA`* → `TOOL_START`/`TOOL_END` → `ANSWER_DELTA`* → `DONE`）。
- 正常：`DONE` 事件的 `answer` 等于所有 `ANSWER_DELTA.text` 拼接结果，且**不含**任何 `THINKING_DELTA` 内容（守 §2.4 的污染红线）。
- 异常：源 `Flux.error(...)` → 产出 `ERROR` 事件、流正常结束、**且断言序列中没有 `DONE`**（守 §2.4 错误路径红线，否则半截答案会入库）。
- 边界：正常完成时 `recorder.clearRecords()` 与 `TenantContext.clear()` 均被调用（池任务 `finally`）。
- 边界（§3.4 取消）：下游取消后 `cancelled` 标志置位、池任务不再发 `DONE`、`finally` 仍完成清理。

### 4.3 `RagAgentServiceStreamTest`（新建）

- 异常：池满（mock `executor.execute` 抛 `RejectedExecutionException`）→ 断言异常从 `processWithHistoryStream(...)` **方法调用本身**抛出，而非从返回的 `Flux` 订阅时抛出。这是 I5 成立的直接证据，也是 §3.1「不用 `subscribeOn`」约束的回归防线。
- 边界：整体超时命中 → 末事件为 `ERROR` 且含提示语，流正常结束，无 `DONE`。
- 边界（I1）：池任务内 `TenantContext.getSchema()` 等于快照值；任务结束后再次读取为空（防线程池串扰）。

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
| R1 | `THINKING_DELTA` 与 `ANSWER_DELTA` 的区分判据不确定（同为 `AGENT_MODEL_STREAMING`） | 中 | 实现阶段先打全量 `OutputType` + `node()` + `agent()` 日志实测一轮再定；候选判据：节点名是否等于 `AGENT_MODEL_NAME`、state 中是否仍有未消费的 tool call、是否为流中最后一个 model 段。最保守回退：合并为单一 `DELTA` 类型，前端不区分思考与答案（功能降级但不阻塞）。 |
| R2 | graph 内部切换调度器导致 `TenantContext` 在节点中途丢失 → 工具访问错误 schema（跨租户风险） | **高** | 实现第一步即验证：在工具内打印 `TenantContext.getSchema()` 与线程名。若丢失，改用 `RunnableConfig.context()`（实测存在，返回可变 `Map<String,Object>`，另有 `clearContext()`）承载租户信息 —— 但**需先验证 graph 是否把该 context 传递到工具的 `ToolContext`**，若不传递则需改为在工具装饰层（`AggregatedToolCallbackProvider`）包装时注入。此路径未验证前，**验证未通过不得合并**。 |
| R3 | `getCompiledGraph()` 非 ReactAgent 对外宣称的稳定 API，升级可能变更 | 中 | 版本已在 `company-rag-agent/pom.xml` 锁死 `1.1.2.0`；`NodeOutputMapper` 为唯一耦合点，升级时改动面可控。 |
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

---

## 7. 已知限制（本方案不解决，明确记录）

1. **Agent 链路 LLM 调用缺熔断包装**，与铁律 R3 不符（既有缺口，非本次引入）。本方案仅覆盖订阅前失败 + 流内错误计数，见 §3.6。
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
