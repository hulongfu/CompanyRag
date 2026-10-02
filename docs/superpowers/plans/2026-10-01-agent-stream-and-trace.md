# 实现计划：Agent 流式输出与执行轨迹（Agent Stream & Trace）

| 属性 | 值 |
| --- | --- |
| 状态 | 待批准 |
| 日期 | 2026-10-01 |
| 来源 spec | `docs/superpowers/specs/2026-10-01-agent-stream-and-trace-design.md`（已冻结） |
| 领域 | Agent 流式输出 / 执行轨迹 / SSE 端点 |
| 涉及模块 | agent / web / bootstrap（+ agent 模块 pom） |
| HARD-GATE | 本计划获批前不落实现代码；获批后**只落本计划范围内的代码** |

---

## 1. 目标

在不改动现有阻塞链路的前提下，新增一条流式链路：消费 `ReactAgent.getCompiledGraph().stream()` 已有的 `NodeOutput` 流，翻译成项目自有事件，经 `POST /api/chat/stream` 以 SSE 输出，并在 `DONE` 时落 `rag_session` + 触发在线评估。

交付物 4 个新文件 + 4 个改动文件 + 3 个新测试类。

---

## 2. 范围边界（不做）

- 不改 `AgentConfig.java`（含 `ReactAgent.builder()` 的构建参数；仅在 R2 处置要求的**注释**上例外，见任务 5.3）。
- 不改现有 `POST /api/chat` 的任何行为、`execute()` 方法、`processWithHistory()` 方法体。
- 不修 §3.7 既有缺陷（阻塞链路 `toolContext` 恒为 traceId）。
- 不补 Agent 链路 LLM 调用的熔断（§3.6 结论：注解式 AOP 包不住 graph 内部调用，属独立议题）。
- 不动 `AggregatedToolCallbackProvider`、审批门、`ExecuteTool`、`DatabaseQueryTool`。
- 不做前端、不新增 DB 列、不引入 AgentScope。

---

## 3. 现状核实结论（已现场确认，行号对应当前代码）

| 项目 | 现状 | 对实现的影响 |
| --- | --- | --- |
| `StreamingAgentExecutor`（75 行） | 仅 `execute(List<Message>)`；`:23` 注释「当前 ReactAgent 不支持流式 API」已过时 | 新增 `executeStream(...)`，`execute()` 零改动；顺带更正该注释 |
| `RagAgentService:183-243` `callAgentWithTimeout` | `ContextSnapshot.captureAll()` + `TenantContext` 五字段手动快照（schema/tenantId/userId/tenantCode/sessionId），子线程 `setThreadLocals()` 恢复 + `finally TenantContext.clear()` | 流式任务的上下文恢复**照抄这段语义**，不另创写法 |
| `RagAgentService:69-101` 池构造 | `ThreadPoolExecutor(core, max, 60s, ArrayBlockingQueue(queueCapacity), AbortPolicy)`，字段 `corePoolSize/maxPoolSize/queueCapacity` 来自 `rag.agent.executor.*` | 流式池复用同一组字段与同款构造，仅新建独立实例 |
| `ChatController:84-124` 安全段 | `X-Tenant-Id` 头 → `verifiedTenantId`（null 则抛 `IllegalArgumentException`）；`SecurityUser` → `verifiedUserId`（null 则抛 `IllegalStateException`） | 流式端点**逐行照搬**该段语义，含异常类型（由 `GlobalExceptionHandler` 转 `R`，HTTP 200） |
| `ChatController:147-156` 落库 | `saveConversation(tenantId, sessionId, userId, query, answer, toolContext, null, null, null)` —— 全部用方法内局部变量，不读 ThreadLocal | 流式落库沿用同一调用形态，租户参数取 controller 局部变量（spec §2.3 约束 1） |
| `ChatController:173-196` 评估触发 | `evalOnlineEnabled && savedRowId != null && answerEvaluationService != null && result.isRagUsed()` → `TenantContextSnapshot.captureNow()` + `evalExecutor.submit`，拒绝时仅告警且**不调 clear** | 流式端点复制该段，条件与拒绝处理语义不变（I3） |
| `RagController:24` | `@PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)` + `return Flux<String>` | WebMVC 栈 SSE 已有先例，新端点同构 |
| `GlobalExceptionHandler` | 全部 handler 返回 `R<Void>`，**无 `@ResponseStatus`** → HTTP 恒 200 | 建流前失败一律 `R.fail(code, msg)`，不得自行用 `ResponseEntity` 改 HTTP 状态 |
| `company-rag-agent/pom.xml` | 依赖仅 common / tenant / web / jdbc / spring-ai-\* / agent-framework(1.1.2.0 硬编码) / jsqlparser / lombok。**无 resilience4j、无 reactor-core、无 micrometer 显式声明、无 reactor-test** | 见任务 1；`@CircuitBreaker` 与 `StepVerifier` 都需要补依赖 |
| `company-rag-agent/src` | 全模块 `reactor.core` 引用数 = 0 | 流式是本模块首次引入 Reactor，`Flux` 类型只允许出现在 `stream` 包与 `executeStream` 签名上 |

### 3.1 graph / agent-framework API 事实（`javap` 实测，版本锁 `1.1.2.0`）

| 事实 | 结论 |
| --- | --- |
| 流式入口 | `CompiledGraph.stream(Map<String,Object> inputs, RunnableConfig)` → `Flux<NodeOutput>`；另有 `graphResponseStream(...)` → `Flux<GraphResponse<NodeOutput>>`。**本次用前者** |
| `ReactAgent` | 无 `stream()`；有 `getCompiledGraph()`（public）。`call()` 6 个重载保持不动 |
| inputs 结构 | `Agent.buildMessageInput(Object)` 为 **protected**（在父类 `Agent`），内部构造 `{"messages": List<Message>, "input": 末条 UserMessage.getText()}`。故流式侧**自行构造该 Map**，key 字面量 `"messages"` / `OverAllState.DEFAULT_INPUT_KEY`（值 `"input"`） |
| `RunnableConfig` 常量 | `AGENT_MODEL_NAME="_AGENT_MODEL_"`、`AGENT_TOOL_NAME="_AGENT_TOOL_"`、`AGENT_HOOK_NAME_PREFIX="_AGENT_HOOK_"`、`AGENT_NAME_KEY="_AGENT_"`。**R1 判据可直接引用，不必硬编码字符串** |
| `RunnableConfig.Builder` | `threadId(String)`、`streamMode(StreamMode)`、`addParallelNodeExecutor(...)`、`defaultParallelExecutor(...)` 等。本次仅用 `threadId(sessionId)` |
| `StreamingOutput<T>` | `chunk()` / `message()` / `getOriginData()` / `getOutputType()`；继承 `NodeOutput` 的 `node()` / `agent()` / `state()` / `tokenUsage()` / `isSTART()` / `isEND()` |
| `OutputType` | 8 值：`AGENT_MODEL_STREAMING/FINISHED`、`AGENT_TOOL_STREAMING/FINISHED`、`AGENT_HOOK_STREAMING/FINISHED`、`GRAPH_NODE_STREAMING/FINISHED` |
| 线程模型 | 见 spec §3.3.1 四项字节码核查：当前配置下 `stream()` 全程同步、跑在订阅者线程。**结论成立的前提是未开 `parallelToolExecution` 且图内无并行节点**，任务 6.2 的防回归断言守这条线 |

---

## 4. 前置实测（任务 0，写代码前必做，产出决定 R1 分支）

R1 是唯一还影响**公开契约**（事件类型是否 6 个）的未定项。用一个临时探针把事实钉死，**不提交**该探针代码。

**0.1 探针做法**：在 `executeStream` 骨架阶段（任务 3 的最初版本）临时把每个 `NodeOutput` 打成日志：

```java
log.info("[STREAM-PROBE] class={} node={} agent={} outputType={} hasToolCalls={} chunk={}",
        o.getClass().getSimpleName(), o.node(), o.agent(),
        o instanceof StreamingOutput<?> so ? so.getOutputType() : "N/A",
        o instanceof StreamingOutput<?> so && so.message() instanceof AssistantMessage am && am.hasToolCalls(),
        o instanceof StreamingOutput<?> so ? so.chunk() : "N/A");
```

**0.2 跑一轮真实请求**（需 LLM 可用）：一次纯问答（不调工具）+ 一次必调 `searchKnowledgeBase` 的问答，收集日志。

**0.3 据实测二选一，并把结论回写 spec §5-R1**：

| 分支 | 触发条件 | 实现差异 |
| --- | --- | --- |
| **A（首选）** | `AGENT_MODEL_STREAMING` 帧的 `message()` 在工具轮就带 `hasToolCalls()==true`，可边流边判 | 6 种事件全保留。`ANSWER_DELTA` = model 增量且 `hasToolCalls()==false`；`THINKING_DELTA` = 同节点但 `hasToolCalls()==true` |
| **B（回退）** | 工具调用的 tool call 只在 `AGENT_MODEL_FINISHED` 才可见，流中无法判定 | 合并为单一 `ANSWER_DELTA`（`THINKING_DELTA` 枚举保留但本期不产出），前端不区分思考与答案。`DONE.answer` 改取 `AGENT_MODEL_FINISHED` 时 `message()` 的 `AssistantMessage.getText()`，**不再靠累加增量** |

> 两个分支下 `DONE.answer` 的来源都必须在任务 3 开始前定稿，不允许实现时随手写。分支 B 同时要求 mapper 在 `AGENT_MODEL_FINISHED` 记录末条 `AssistantMessage` 文本。

**0.4 顺带确认**：`AGENT_TOOL_STREAMING` 是否真的会发（若不发，`TOOL_START` 只能由 `AGENT_TOOL_FINISHED` 单点合成，则 `TOOL_START` 需降级为「不产出」并在 §6 配置说明里记录）。

---

## 5. 实现任务

### 任务 1：依赖与配置骨架

**1.1 `company-rag-agent/pom.xml`** 补 3 项（版本全部由父 pom 的 `spring-boot-dependencies` / `spring-ai-alibaba-bom` 管理，**不写 version**，与 rag 模块 `resilience4j-spring-boot3` 的写法一致）：

| 依赖 | 用途 | scope |
| --- | --- | --- |
| `io.projectreactor:reactor-core` | `Flux` / `Sinks`。当前靠 agent-framework 传递引入，本模块要直接使用必须显式声明 | compile |
| `io.github.resilience4j:resilience4j-spring-boot3` | 流式入口 `@CircuitBreaker`（spec §3.6） | compile |
| `io.projectreactor:reactor-test` | `StepVerifier` | test |

`MeterRegistry` 由 `spring-boot-starter-web` + actuator（bootstrap 已引入）在运行期提供，`micrometer-core` 经传递可得；若编译不过再显式补 `io.micrometer:micrometer-core`，**先不预先加**。

**1.2 `company-rag-bootstrap/src/main/resources/application.yml`** 在 `rag.agent` 下新增（**必须写在基段，不加 `matchIfMissing`**，理由见 spec §3.8）：

```yaml
  agent:
    executor:
      # ...（现有四项不动）
    stream:
      # 流式端点总开关。关闭时 /api/chat/stream 返回 R.fail(503,...)，不建流。
      # 铁律：必须在基段显式写出，profile 不补写；曾因缺键导致端点 404 而非可读错误。
      enabled: false
      # 相邻事件最大间隔（秒），治「模型 hang 住但连接不断」
      idle-timeout-seconds: 60
```

同时检查 `application-prod.yml` / `application-test.yml` 是否有 `rag.agent` 段：若有，确认新增键不会被子段整体覆盖（YAML profile 是深合并，但需确认项目未用 `spring.config` 的替换语义）。

**1.3 流式专用线程池**：落在 `RagAgentService` 内，与现有 `executorService` 并列新增字段 `streamExecutor`，在**同一个 `@PostConstruct`** 里按同款 `ThreadPoolExecutor(core, max, 60s, ArrayBlockingQueue(queueCapacity), AbortPolicy)` 构造，线程名前缀区分（如 `rag-agent-stream-`）便于日志与 §6.2 断言。

> 为什么不复用现有超时池：流式任务含审批等待会长时间占线程，混池会与阻塞链路互相饿死（spec §3.1）。

---

### 任务 2：事件模型 + `NodeOutputMapper`（先写测试）

**新建 4 个文件**（包 `com.company.rag.agent.stream`）：

**2.1 `AgentStreamEventType.java`**（枚举）：`THINKING_DELTA` / `TOOL_START` / `TOOL_END` / `ANSWER_DELTA` / `DONE` / `ERROR`。

**2.2 `AgentStreamEvent.java`**（record）：

```java
public record AgentStreamEvent(
        AgentStreamEventType type,
        String text,          // 增量文本；非文本事件为 null
        String toolName,      // 仅 TOOL_START/TOOL_END
        Long durationMs,      // 仅 TOOL_END
        String status,        // 仅 TOOL_END
        AgentResult result    // 仅 DONE：answer / toolContext / ragUsed
) {
    // 静态工厂：thinkingDelta / answerDelta / toolStart / toolEnd / done / error
}
```

`DONE` 的 SSE 帧**不重复携带 answer 全文**（前端已逐帧收到 `ANSWER_DELTA`）；`AgentResult` 只在 controller 的 `doOnNext` 内消费，序列化到前端时须排除（`AgentStreamEvent` 上加 `@JsonInclude(NON_NULL)`，且 `result` 字段标 `@JsonIgnore` —— 轨迹数据不外泄，避免与 `rag_session` 落库内容形成两条不一致出口）。

**2.3 `NodeOutputMapper.java`**（`@Component`）：**全项目唯一**允许 import `NodeOutput` / `StreamingOutput` / `OutputType` 的文件。

- 主方法：`List<AgentStreamEvent> map(NodeOutput output)`。返回列表而非单个，因单个 `NodeOutput` 可能需展开为多事件（如 `TOOL_END` + 工具结果文本）。
- 入站先过滤：`!(output instanceof StreamingOutput)` → 返回 `List.of()`（裸 `NodeOutput` 是 `GRAPH_NODE_*` 生命周期帧，忽略）。`isSTART()/isEND()` 同样忽略。
- 分派按 `getOutputType()`：
  - `AGENT_MODEL_STREAMING` → 按任务 0 的分支结论产 `ANSWER_DELTA` / `THINKING_DELTA`；`chunk()` 为 null 或空 → 返回空列表（不产噪声帧）。
  - `AGENT_MODEL_FINISHED` → 分支 B 下记录末条 `AssistantMessage` 文本到 mapper 内部**按调用隔离**的状态。
  - `AGENT_TOOL_FINISHED` → `TOOL_END`（`toolName` 取 `node()`/`agent()`，`durationMs`/`status` 见下）。
  - `AGENT_TOOL_STREAMING` → `TOOL_START`（若任务 0.4 实测确认不发，则本期不产出，`TOOL_START` 枚举保留）。
  - `AGENT_HOOK_STREAMING/FINISHED` → 返回空列表。节点名以 `RunnableConfig.AGENT_HOOK_NAME_PREFIX` 开头即技能钩子，**绝不可误判为工具**（否则 `SkillsAgentHook` 会被前端显示成工具调用）。
  - `GRAPH_NODE_*` → 空列表。
- 判节点类型一律用 `RunnableConfig.AGENT_MODEL_NAME` / `AGENT_TOOL_NAME` / `AGENT_HOOK_NAME_PREFIX` 常量，**不硬编码字面量**。
- **不抛异常**：任何无法识别的 `OutputType` 走 `log.warn` + 返回空列表（一条未知帧不能打断整条流）。

**`durationMs` / `status` 的可得性风险**：`NodeOutput` 只有 `node()/agent()/state()/tokenUsage()`，**没有耗时字段**。`status` 可从 state 中工具结果推断，`durationMs` 需 mapper 自己在 `TOOL_START` 与 `TOOL_END` 之间用时间戳差算。若任务 0.4 确认 `TOOL_START` 不发，则 `durationMs` 无来源 → **置 null，不编造数值**，并在 spec §6 记录该限制。`status` 同理：拿不到可靠依据时置 null，不用 "SUCCESS" 填充。

**2.4 mapper 的有状态问题**（必须处理，否则并发串号）：若分支 B 需要「记录末条 AssistantMessage」、或 `durationMs` 需要跨事件配对，mapper 就**不能再是单例 Bean 的无状态方法**。处置：**mapper 保持无状态**，把跨事件累积职责移到任务 3 的池任务局部变量（`StringBuilder answer`、`Map<String,Long> toolStartAt`、`AtomicReference<String> lastAssistantText`）。mapper 只做「单帧 → 事件列表」的纯翻译。这条约束写进 mapper 的类注释。

**验证（先红后绿，scoped）**：
```
mvn -q -pl company-rag-agent test -Dtest=NodeOutputMapperTest
```

---

### 任务 3：`StreamingAgentExecutor.executeStream(...)`

**3.1 方法签名**（`StreamingAgentExecutor` 新增，`execute()` 零改动）：

```java
public Flux<AgentStreamEvent> executeStream(List<Message> messages, String sessionId,
        AtomicBoolean cancelled, TenantStreamContext ctx)
```

- 返回 `Flux`，但**池满异常从本方法调用本身同步抛出**（`RejectedExecutionException`），不是订阅时 —— 这是 I5 的实现要点（spec §3.1）。
- `TenantStreamContext`：新增小 record（放 `stream` 包），承载 `ContextSnapshot` + `TenantContext` 五字段。照抄 `RagAgentService:188-194` 的捕获语义。**放在 agent 模块内，不放 common**（仅此处使用）。

**3.2 池任务主体**（严格按 spec §2.4 的伪代码，逐条落实）：

1. `sink = Sinks.many().unicast().onBackpressureBuffer()`，**方法体内**先 `streamExecutor.execute(task)` 再 `return sink.asFlux()`。
2. 任务内顺序：恢复上下文（`snapshot.setThreadLocals()` try-with-resources + 五字段判空 set）→ 构造 `inputs = Map.of("messages", messages, "input", 末条 UserMessage.getText())` → `RunnableConfig.builder().threadId(sessionId).build()` → `reactAgent.getCompiledGraph().stream(inputs, cfg)`。
3. 管道：`.map(mapper::map).flatMapIterable(...)` → `doOnNext` 内先查 `cancelled`（真则抛 `CancellationException`）→ 累加 `ANSWER_DELTA` 到 `StringBuilder` → `sink.tryEmitNext`。
4. 两层超时：`.timeout(Duration.ofSeconds(idleTimeoutSeconds))`（相邻间隔）+ 整体上限由 `blockLast(Duration.ofMinutes(agentTimeoutMinutes))` 承担。
5. `onErrorResume`：置 `errored=true` + 发 `ERROR` + `registry.counter("rag.agent.stream.error").increment()` + `return Flux.empty()`。
6. `blockLast()` 之后：`if (!errored) sink.tryEmitNext(buildDone(...))` —— **错误路径不发 DONE**（否则半截答案入库，违反 §3.5）。
7. `catch (CancellationException)` → 只 `tryEmitComplete()`，**不发 ERROR/DONE**。
8. `catch (Exception)` → 发 `ERROR` + `tryEmitComplete()`。**绝不让异常穿透**（I4：SSE 头已写出，穿透到 `GlobalExceptionHandler` 会往已提交响应写 JSON 造成脏帧）。
9. `finally`：`recorder.clearRecords()` + `TenantContext.clear()`（`ContextSnapshot.Scope` 由 try-with-resources 自动还原）。

**3.3 `DONE` 内容**：`AgentResult.builder().answer(answer.toString()).toolContext(recorder.captureToolContext()).ragUsed(recorder.usedTool("searchKnowledgeBase")).build()` —— 三个取值**必须在池任务线程内、`finally` 清理之前**完成（`ToolCallRecorder` 是 ThreadLocal，见 spec §2.4）。`ragUsed` 的工具名沿用 `"searchKnowledgeBase"` 字面量，与 `execute():57` 完全一致（I3）。

**3.4 `execute()` 上方注释更正**：`StreamingAgentExecutor.java:23-24`「当前 ReactAgent 不支持流式 API，真正的流式支持需要在 Spring AI Alibaba 层面实现」已被事实推翻（`getCompiledGraph().stream()` 即为流式入口），改为说明流式入口在 graph 层。

**验证**：
```
mvn -q -pl company-rag-agent test -Dtest=StreamingAgentExecutorStreamTest
```

---

### 任务 4：`RagAgentService.processWithHistoryStream(...)`

**4.1 签名**：

```java
@CircuitBreaker(name = "rag-agent", fallbackMethod = "processWithHistoryStreamFallback")
public Flux<AgentStreamEvent> processWithHistoryStream(List<Message> history, String userMessage)
```

`fallbackMethod` 签名须为 `(List<Message>, String, Throwable)` 且返回 `Flux<AgentStreamEvent>`，返回 `Flux.just(AgentStreamEvent.error(...))` —— 熔断打开时**建流前**就失败，走 I5 路径由 controller 转 `R.fail`。

> 注意：`@CircuitBreaker` 生效前提是异常**同步抛出**（本设计已保证池满等同步抛出，spec §3.6 第 1 点）。若 AOP 代理因内部自调用失效（本方法是 controller 直接调用，属正常代理路径，预期无问题），任务 4 验收须实测确认熔断日志出现，不得只依赖注解存在即认为已满足 R3。

**4.2 方法体**：与 `processWithHistory:126-146` 同构构造 `messages`（history + `UserMessage`），捕获 `TenantStreamContext`，委托 `streamingAgentExecutor.executeStream(messages, sessionId, cancelled, ctx)`。

`sessionId` 从 `TenantContext.getSessionId()` 取（controller 已 set），用于 `RunnableConfig.threadId`。

**4.3 不复制的东西**：`processWithHistory` 的 `catch (Exception)` 返回「抱歉，系统繁忙」兜底 `AgentResult`（`:162-170`）**不得照搬** —— 流式链路的错误必须以 `ERROR` 事件表达，返回兜底答案文本会让 controller 误判为正常完成并落库。

---

### 任务 5：`ChatController` 流式端点

**5.1 端点**：

```java
@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
@PreAuthorize("isAuthenticated()")
public Object chatStream(@RequestBody ChatRequest request,
        @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId)
```

返回类型用 `Object` 是有意的：建流前失败要返回 `R<ChatResponse>`，成功返回 `Flux<AgentStreamEvent>`，两者无法统一到一个具体类型。**这是本设计唯一偏离「所有 API 返回 `R<T>`」的端点**，理由与边界见 spec §3.1/§3.2（铁律 R4 的既有例外，`RagController:24` 同类）。

**5.2 方法体顺序**（顺序即正确性，不可调整）：

1. 开关判断最先：`if (!streamEnabled) return R.fail(503, "流式接口未启用");` —— 不建流、不读历史、不占池。
2. `TenantContext.setSessionId(...)`。
3. 安全校验段**逐行照搬** `:92-123`（`verifiedTenantId` 非空、`SecurityUser` 取 `verifiedUserId`、两者写回 `request` 与 `TenantContext`）。异常类型保持 `IllegalArgumentException` / `IllegalStateException` 不变，由 `GlobalExceptionHandler` 转 `R`（HTTP 200）。
4. 读历史：与 `:128-141` 同条件（`sessionId != null && tenantId != null` 才读 `ragChatMemory.get(...)`）。
5. `AtomicBoolean cancelled = new AtomicBoolean(false)`。
6. `try { flux = ragAgentService.processWithHistoryStream(history, query); } catch (RejectedExecutionException | CallNotPermittedException e) { log.warn(...); return R.fail(503, "系统繁忙，请稍后重试"); }` —— **I5 的落点**：此刻尚未返回 `Flux`，Spring 未开始写 SSE 头，可以正常返回统一响应体。
7. 返回前挂回调（在**方法内**挂，不在 service 内挂）：

```java
return flux.doOnCancel(() -> cancelled.set(true))
           .doOnNext(ev -> { if (ev.type() == DONE) onStreamCompleted(ev.result(), ...局部变量...); });
```

**5.3 `onStreamCompleted` 私有方法**：把现有 `chat()` 的落库段（`:147-156`）与评估段（`:169-196`）抽成私有方法，**阻塞端点与流式端点共用**（这是本次唯一允许触碰阻塞链路的一处，且必须是**纯提取、行为等价**；若评估风险偏高，则改为流式端点内复制一份，不抽公共方法 —— 由任务获批时用户裁决，默认取**复制不抽取**，改动面更小）。

约束：该方法内**禁止**读任何 ThreadLocal（`TenantContext` / `ToolCallRecorder`），租户/用户/会话三个参数全部由 controller 方法内已校验的局部变量显式传入（`saveConversation` 本就接收这三个参数，无需 ThreadLocal）。原因见 spec §2.3 约束 2：回调在下游订阅者线程执行，不保证是池线程。

**5.4 `AgentConfig` 注释**（R2 处置）：在 `AgentConfig.java:90` 的 `ReactAgent.builder()` 上方加一行说明 —— 「不得开启 `parallelToolExecution` 或在图内加入并行节点：流式链路依赖工具在订阅线程内同步执行（`TenantContext`/`ToolCallRecorder` 为 ThreadLocal），开启并行会静默导致跨租户风险。约束由 `RagAgentServiceStreamTest` 的线程归属断言守护」。

---

### 任务 6：测试（对应 spec §4，JUnit 5 + Mockito，命名 `{methodName}_{scenario}_{expectedResult}`）

**6.1 `NodeOutputMapperTest`**（新建，纯单测，价值最高）—— 覆盖 spec §4.1 六场景表：中间轮 model 增量、末轮 model 增量、`AGENT_TOOL_FINISHED` 产 `TOOL_END`、`chunk()` 为 null/空串产空列表、`AGENT_HOOK_*` 不误判为工具、裸 `NodeOutput` 忽略且不抛。

**6.2 `StreamingAgentExecutorStreamTest`**（新建，mock `reactAgent.getCompiledGraph()` 返回固定 `Flux<NodeOutput>`）：

- `executeStream_normalFlow_emitsEventsInOrder` —— `StepVerifier` 断言序列。
- `executeStream_success_doneAnswerContainsOnlyAnswerDeltas` —— `DONE.result.answer` == 所有 `ANSWER_DELTA.text` 拼接，且**不含** `THINKING_DELTA` 内容（守 §2.4 污染红线）。
- `executeStream_sourceError_emitsErrorAndNoDone` —— 源 `Flux.error` → 有 `ERROR`、流正常结束、**断言序列中没有 `DONE`**（守错误路径红线）。
- `executeStream_completion_clearsRecorderAndTenantContext` —— 正常完成时 `clearRecords()` 与 `TenantContext.clear()` 均被调用。
- `executeStream_clientCancelled_noDoneAndStillCleansUp` —— `cancelled` 置位后不发 `DONE`、`finally` 仍清理。

**6.3 `RagAgentServiceStreamTest`**（新建）：

- `processWithHistoryStream_poolSaturated_throwsFromMethodCallNotOnSubscribe` —— mock `streamExecutor.execute` 抛 `RejectedExecutionException`，断言异常从**方法调用本身**抛出。这是 I5 成立的直接证据，也是「不用 `subscribeOn`」的回归防线。
- `processWithHistoryStream_idleTimeout_emitsErrorWithoutDone`。
- `processWithHistoryStream_restoresTenantContext_inPoolThreadOnly` —— 池任务内 `TenantContext.getSchema()` == 快照值；任务结束后再读为空（I1 + 防线程池串扰）。
- `processWithHistoryStream_toolExecutesOnSameThreadAsPoolTask` —— **防回归断言（守 §3.3.1 的线程归属前提）**：用测试工具回调记录执行线程名，断言与池任务线程名相同。一旦有人开启 `parallelToolExecution` 或加并行节点，本用例立刻红 → 此时 ThreadLocal 方案静默失效、有跨租户风险，必须改走显式传递，不得直接合并。

**6.4 不做**（YAGNI）：不引入 MockChatModel 做全链路集成测试；不为 `AgentStreamEvent`/`AgentStreamEventType` 纯数据 record 写测试；不写前端 SSE 契约测试；不为 `ChatController` 新增 `@WebMvcTest`（SSE 端点自动化收益低于成本，改由 6.5 手工验收覆盖）。

---

### 任务 7：手工验收（不可自动化，须逐条执行并留证据）

1. `rag.agent.stream.enabled=true`，`curl -N -X POST /api/chat/stream -H "X-Tenant-Id: 1" -H "Authorization: Bearer <jwt>" -d '{"query":"...","sessionId":"s1"}'` —— 肉眼确认 token 逐帧到达，而非最后一次性吐出。
2. 触发需审批的工具（`execute`），审批等待期间并发打普通 `/api/chat` —— 确认阻塞端点正常响应（验证 §3.1 未拖死 HTTP 线程、两池隔离）。
3. 中途 `Ctrl+C` 断开 curl —— 查 `rag_session` **未新增行**、无评估记录（验证 §3.5「宁缺不残」）。
4. 开关置 `false` 重打 —— 返回 `R.fail(503,...)` 且 HTTP 200，**不是 404**（验证 §3.8 配置落地要求）。
5. 触发一次熔断（临时把池容量压到 0 或制造连续失败）—— 日志出现 `rag-agent` 熔断记录，端点返回 `R.fail`（验证 §3.6 第 1 点真的生效，而非只有注解）。

---

## 6. 提交顺序建议

按可独立验证的最小单元切，每步都能单独编译 + 跑对应测试：

1. 依赖 + 配置 + 流式池（任务 1）→ `mvn -q -pl company-rag-agent -am -DskipTests compile`
2. 事件模型 + mapper + `NodeOutputMapperTest`（任务 2，先红后绿）
3. `executeStream` + `StreamingAgentExecutorStreamTest`（任务 3）
4. `processWithHistoryStream` + `RagAgentServiceStreamTest`（任务 4、6.3）
5. 端点 + `AgentConfig` 注释（任务 5）
6. 手工验收（任务 7）+ 回写 spec §5-R1 实测结论

**验证纪律**：全程 scoped 到 `company-rag-agent`（及任务 5 后的 `company-rag-web`），不跑全仓 `mvn test`。

---

## 7. 风险与回滚

- 本计划全部改动受 `rag.agent.stream.enabled=false` 保护，默认关闭即线上零影响；回滚 = 关开关，无需回滚代码。
- 唯一侵入既有代码的两处：`StreamingAgentExecutor` 的过时注释更正（无行为影响）、`AgentConfig` 新增一行注释（无行为影响）。任务 5.3 默认取「复制不抽取」，故**阻塞链路 `ChatController.chat()` 方法体一行不动**。
- 剩余不确定项仅 R1（事件类型是否退化为 5 种），由任务 0 在写代码前关闭，不留到实现中途。
