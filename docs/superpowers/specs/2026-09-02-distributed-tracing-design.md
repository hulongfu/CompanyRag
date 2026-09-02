# 分布式追踪设计文档 — CompanyRag

**日期**: 2026-09-02  
**状态**: 已批准  
**作者**: AI Assistant  
**审核**: 待用户审核

---

## 1. 背景与问题

### 1.1 现状

当前项目无分布式追踪能力：
- 全仓无 OTel/Sleuth 依赖
- logback pattern 无 `%X{traceId}` 变量
- 跨 Agent → MCP → LLM 的调用链无法串联
- 出问题时只能靠猜，无法通过统一 traceId 定位问题

### 1.2 问题影响

1. **调试困难**：无法通过日志快速定位请求的完整调用链路
2. **问题排查低效**：跨模块调用失败时，需要手动拼凑各模块日志
3. **性能分析缺失**：无法准确测量每个环节的耗时
4. **不符合企业级标准**：缺少可观测性三大支柱中的"链路追踪"

---

## 2. 设计目标

### 2.1 核心目标

1. **全链路追踪**：从 HTTP 请求入口到 LLM 调用、DB 查询，全程 traceId 贯穿
2. **日志增强**：所有日志自动输出 `traceId` 和 `spanId`
3. **异步支持**：线程池/异步场景正确传递追踪上下文
4. **最小改动**：利用 Spring Boot 3.2+ 原生支持，避免重复造轮子

### 2.2 非目标（本期不做）

1. **不配置外部 exporter**：不导出到 Jaeger/Zipkin/Tempo 等外部系统
2. **不改变现有监控体系**：保留现有的 Micrometer + Prometheus 指标收集
3. **不删除 ToolCallRecorder**：保留其工具调用记录/聚合的业务功能

---

## 3. 架构设计

### 3.1 技术选型

采用 **Micrometer Tracing + OpenTelemetry** 方案：

```
┌────────────────────────────────────────────────────────────┐
│  Spring Boot 3.4 + Spring AI 1.0                            │
│      ↓                                                      │
│  Micrometer Tracing (Spring 官方追踪抽象)                    │
│      ↓                                                      │
│  OpenTelemetry Bridge (OTel Java Agent 兼容层)              │
│      ↓                                                      │
│  MDC (SLF4J) - 日志自动输出 traceId/spanId                  │
└────────────────────────────────────────────────────────────┘
```

**选型理由**：
- Spring Boot 3.2+ 原生支持，配置简单
- 与现有 Micrometer + Prometheus 技术栈一致
- Spring AI 已集成 Micrometer，LLM 调用自动埋点
- 符合企业级标准实践

### 3.2 调用链路

```
HTTP 请求 → ChatController → RagAgentService → (异步线程池) → 
StreamingAgentExecutor → Agent (LLM) → Tool → RAG Service / DB
    ↓            ↓              ↓              ↓           ↓
 traceId 贯穿整个调用链 (MDC 自动注入日志)
```

### 3.3 组件职责

| 组件 | 职责 | 改动 |
|------|------|------|
| **Micrometer Tracing** | 自动生成 traceId/spanId，管理 MDC 上下文 | 新增依赖 |
| **logback-spring.xml** | 日志 pattern 增加 `%X{traceId}` `%X{spanId}` | 修改配置 |
| **RagAgentService** | 异步场景手动传递 MDC 上下文 | 修改代码 |
| **ToolCallRecorder** | 从 MDC 获取 traceId，记录工具调用 | 简化代码 |

---

## 4. 详细设计

### 4.1 依赖配置

**文件**: `company-rag-bootstrap/pom.xml`

```xml
<!-- Micrometer Tracing + OpenTelemetry -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

**说明**：
- `micrometer-tracing-bridge-otel`：Micrometer Tracing 的 OTel 桥接实现
- `opentelemetry-exporter-otlp`：OTel 导出器（本期仅用于生成 trace/span，不配置外部导出）

### 4.2 日志配置

**文件**: `company-rag-bootstrap/src/main/resources/logback-spring.xml`

**修改前**：
```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
```

**修改后**：
```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - traceId=%X{traceId} spanId=%X{spanId} - %msg%n</pattern>
```

**说明**：
- `%X{traceId}`：从 MDC 获取 traceId（由 Micrometer Tracing 自动设置）
- `%X{spanId}`：从 MDC 获取 spanId（当前操作的唯一标识）
- 所有三个 appender（CONSOLE、FILE、ERROR_FILE）都需要修改

### 4.3 异步上下文传递

**文件**: `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`

**问题**：Spring Web 自动创建的 trace 不会自动传递到线程池

**解决方案**：使用 `Runnable` 包装捕获 MDC 上下文

**修改前**：
```java
private AssistantMessage callAgentWithTimeout(List<Message> messages) throws GraphRunnerException, Exception {
    try {
        // 捕获当前线程的租户上下文和会话上下文
        String tenantSchema = TenantContext.getSchema();
        Long tenantId = TenantContext.getTenantId();
        // ... 其他上下文

        CompletableFuture<AssistantMessage> future = CompletableFuture
                .supplyAsync(() -> {
                    try {
                        // 在子线程中恢复租户上下文
                        if (tenantSchema != null) {
                            TenantContext.setSchema(tenantSchema);
                        }
                        // ... 恢复其他上下文
                        return streamingAgentExecutor.execute(messages);
                    } finally {
                        TenantContext.clear();
                    }
                }, executorService);
        return future.get(AGENT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }
    // ...
}
```

**修改后**：
```java
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;

private final Tracer tracer;

public RagAgentService(StreamingAgentExecutor streamingAgentExecutor,
                       ToolCallRecorder recorder,
                       Tracer tracer) {  // 新增 Tracer 注入
    this.streamingAgentExecutor = streamingAgentExecutor;
    this.recorder = recorder;
    this.tracer = tracer;
}

private AssistantMessage callAgentWithTimeout(List<Message> messages) throws GraphRunnerException, Exception {
    try {
        // 捕获当前线程的 MDC 上下文和租户上下文
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        String tenantSchema = TenantContext.getSchema();
        Long tenantId = TenantContext.getTenantId();
        // ... 其他上下文

        // 获取当前 span 用于创建子 span
        Span parentSpan = tracer.currentSpan();

        CompletableFuture<AssistantMessage> future = CompletableFuture
                .supplyAsync(() -> {
                    // 恢复 MDC 上下文
                    if (mdcContext != null) {
                        MDC.setContextMap(mdcContext);
                    }
                    
                    // 恢复租户上下文
                    if (tenantSchema != null) {
                        TenantContext.setSchema(tenantSchema);
                    }
                    // ... 恢复其他上下文

                    // 创建子 span（可选，用于更细粒度的追踪）
                    Span childSpan = tracer.nextSpan(parentSpan).name("agent-async-execution").start();
                    try (Tracer.SpanInScope scope = tracer.withSpan(childSpan)) {
                        return streamingAgentExecutor.execute(messages);
                    } finally {
                        childSpan.end();
                        MDC.clear();
                        TenantContext.clear();
                    }
                }, executorService);
        return future.get(AGENT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }
    // ...
}
```

**说明**：
- 使用 `MDC.getCopyOfContextMap()` 捕获当前 traceId/spanId
- 在子线程中通过 `MDC.setContextMap()` 恢复
- 可选：使用 `Tracer` 创建子 span，实现更细粒度的追踪

### 4.4 ToolCallRecorder 简化

**文件**: `company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java`

**删除的方法**：
- `generateTraceId()` - 交给 OTel 框架生成
- `setTraceId(String traceId)` - 使用 MDC 自动管理
- `getTraceId()` - 改为从 MDC 获取
- `clearTraceId()` - MDC 由框架自动清理

**修改的方法**：
```java
import org.slf4j.MDC;

/**
 * 记录工具调用开始
 * @return 开始时间戳（毫秒）
 */
public long recordStart(String toolName, Map<String, Object> arguments) {
    String traceId = MDC.get("traceId");  // 从 MDC 获取
    String inputSummary = arguments != null
            ? arguments.toString().substring(0, Math.min(arguments.toString().length(), MAX_INPUT_LENGTH))
            : "";
    log.info("[TOOL_START] traceId={}, tool={}, input={}", traceId, toolName, inputSummary);
    return System.currentTimeMillis();
}

/**
 * 记录工具调用结束
 */
public void recordEnd(String toolName, long startTimeMs, String status) {
    recordEnd(toolName, startTimeMs, status, null);
}

/**
 * 记录工具调用结束（带错误信息）
 */
public void recordEnd(String toolName, long startTimeMs, String status, String errorMessage) {
    String traceId = MDC.get("traceId");  // 从 MDC 获取
    long durationMs = System.currentTimeMillis() - startTimeMs;

    ToolCallRecord record = ToolCallRecord.builder()
            .traceId(traceId)
            .toolName(toolName)
            .durationMs(durationMs)
            .status(status)
            .errorMessage(errorMessage)
            .build();

    List<ToolCallRecord> records = recordsHolder.get();
    if (records == null) {
        records = new ArrayList<>();
        recordsHolder.set(records);
    }
    records.add(record);

    if (errorMessage != null) {
        log.warn("[TOOL_END] traceId={}, tool={}, duration={}ms, status={}, error={}",
                traceId, toolName, durationMs, status, errorMessage);
    } else {
        log.info("[TOOL_END] traceId={}, tool={}, duration={}ms, status={}",
                traceId, toolName, durationMs, status);
    }
}
```

**保留的方法**：
- `getAndClearRecords(String traceId)` - 保留聚合功能（但参数 traceId 改为可选）
- `recordsHolder` ThreadLocal - 保留工具调用记录

### 4.5 RagAgentService 简化

**文件**: `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`

**删除的代码**：
```java
// 删除：生成 traceId 并设置到当前线程
String traceId = recorder.generateTraceId();
recorder.setTraceId(traceId);

// 删除：finally 块中的清理
recorder.clearTraceId();
```

**修改后的代码**：
```java
public AgentResult processWithHistory(List<Message> history, String userMessage) {
    // 不再需要手动管理 traceId，框架会自动处理
    long requestStart = System.currentTimeMillis();

    // traceId 会自动从 MDC 获取
    log.info("[AGENT] userMsg=\"{}\", historySize={}", 
            userMessage, history != null ? history.size() : 0);

    try {
        // ... 业务逻辑不变

        // 聚合工具调用记录
        long totalMs = System.currentTimeMillis() - requestStart;
        List<ToolCallRecord> records = recorder.getAndClearRecords();  // 不再需要传 traceId
        String toolsSummary = records.stream()
                .map(r -> String.format("%s(%dms,%s)", r.getToolName(), r.getDurationMs(), r.getStatus()))
                .collect(Collectors.joining(", "));
        log.info("[AGENT] tools=[{}], total={}ms", toolsSummary, totalMs);

        return new AgentResult(response != null ? response : "", MDC.get("traceId"));

    } catch (Exception e) {
        long totalMs = System.currentTimeMillis() - requestStart;
        log.error("[AGENT] total={}ms, error={}", totalMs, e.getMessage(), e);
        return new AgentResult("抱歉，系统繁忙，请稍后重试。", "error:" + e.getMessage());
    }
}
```

---

## 5. 数据流

### 5.1 正常请求流程

```
1. HTTP 请求到达 ChatController
   ↓ Spring Web 自动创建 trace
   MDC: {traceId="abc123", spanId="def456"}

2. ChatController 记录日志
   ↓ logback pattern 自动输出
   "traceId=abc123 spanId=def456 - 收到聊天请求"

3. RagAgentService 处理请求
   ↓ 从 MDC 获取 traceId
   log.info("[AGENT] userMsg=xxx")  // 自动带 traceId

4. 提交异步任务到线程池
   ↓ 捕获 MDC 上下文
   Map<String, String> mdcContext = MDC.getCopyOfContextMap();

5. 子线程执行 Agent 调用
   ↓ 恢复 MDC 上下文
   MDC.setContextMap(mdcContext);
   
6. Tool 调用（KnowledgeBaseTool 等）
   ↓ ToolCallRecorder 从 MDC 获取 traceId
   log.info("[TOOL_START] traceId=abc123, tool=searchKnowledgeBase")

7. Spring AI 调用 LLM
   ↓ Spring AI 自动创建 span
   MDC: {traceId="abc123", spanId="ghi789"}

8. 返回结果，清理上下文
   ↓ MDC.clear() + TenantContext.clear()
```

### 5.2 异常处理流程

```
1. 任何环节抛出异常
   ↓ 异常被捕获时 traceId 仍在 MDC 中

2. 记录错误日志
   ↓ log.error("[AGENT] error={}", e.getMessage(), e)
   自动输出："traceId=abc123 spanId=def456 - [AGENT] error=xxx"

3. 清理上下文
   ↓ finally 块中 MDC.clear()
```

---

## 6. 错误处理

### 6.1 MDC 上下文丢失

**场景**：异步线程未正确恢复 MDC

**处理**：
- 在 `supplyAsync()` 的 `finally` 块中强制清理
- 使用 `try-with-resources` 管理 `Tracer.SpanInScope`

### 6.2 traceId 为空

**场景**：非 HTTP 请求（如定时任务）

**处理**：
- Micrometer Tracing 会自动为任何操作创建 trace
- 如果 MDC 中 traceId 为空，日志会显示 `traceId=`（空字符串），不影响系统运行

### 6.3 线程池复用导致上下文污染

**场景**：线程池复用旧线程，残留上一个请求的 MDC

**处理**：
- 每次异步任务结束时强制 `MDC.clear()`
- 每次异步任务开始时强制 `MDC.setContextMap(capturedContext)`

---

## 7. 测试策略

### 7.1 单元测试

**测试文件**: `company-rag-common/src/test/java/com/company/rag/common/tool/ToolCallRecorderTest.java`

**测试用例**：
1. `recordStart_fromMdc_getsTraceId()` - 验证从 MDC 获取 traceId
2. `recordEnd_fromMdc_getsTraceId()` - 验证从 MDC 获取 traceId
3. `getAndClearRecords_aggregatesAllRecords()` - 验证记录聚合

### 7.2 集成测试

**测试文件**: `company-rag-agent/src/test/java/com/company/rag/agent/service/RagAgentServiceTest.java`

**测试用例**：
1. `processWithHistory_traceIdPresentInLogs()` - 验证日志中包含 traceId
2. `processWithHistory_asyncMdcContextPropagated()` - 验证异步场景 MDC 正确传递
3. `processWithHistory_exception_traceIdInErrorLog()` - 验证异常日志包含 traceId

### 7.3 端到端测试

**测试场景**：
1. 发起 HTTP 请求 → 检查所有日志行都有相同的 traceId
2. 验证不同层级的 spanId 不同（区分调用层次）
3. 验证异步场景 traceId 一致

---

## 8. 部署与配置

### 8.1 环境变量（可选）

本期不需要特殊配置，Micrometer Tracing 会自动工作。

未来如需导出到外部系统，可添加：
```bash
# OTel Collector 地址
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
# 服务名称
OTEL_SERVICE_NAME=company-rag
# 采样率（0.0-1.0）
OTEL_TRACES_SAMPLER=parentbased_always_on
```

### 8.2 验证步骤

1. **启动应用**：`mvn spring-boot:run`
2. **发起请求**：`curl -X POST http://localhost:8080/api/chat -H "X-Tenant-Id: 1" -d '{"query":"测试"}'`
3. **检查日志**：确认所有日志行都包含 `traceId=xxx spanId=yyy`
4. **验证一致性**：同一请求的所有日志应该有相同的 traceId

---

## 9. 性能影响

### 9.1 预期开销

- **traceId 生成**：纳秒级（UUID 生成）
- **MDC 操作**：微秒级（ThreadLocal 读写）
- **span 创建**：微秒级（内存操作）
- **总体影响**：< 1% 的性能开销（可忽略不计）

### 9.2 日志量增加

**每条日志增加**：约 30-50 字符（`traceId=xxx spanId=yyy - `）

**影响**：
- 日志文件大小增加约 10-15%
- 在可接受范围内

---

## 10. 风险与缓解

### 10.1 风险：异步场景 MDC 传递遗漏

**影响**：部分日志缺少 traceId

**缓解**：
- 审查所有异步代码点（线程池、CompletableFuture、@Async）
- 统一使用 `MDC.getCopyOfContextMap()` + `MDC.setContextMap()` 模式
- 添加测试用例覆盖异步场景

### 10.2 风险：与现有代码冲突

**影响**：ToolCallRecorder 的 ThreadLocal 与 MDC 并存导致混乱

**缓解**：
- 明确职责边界：MDC 负责 traceId，ThreadLocal 负责工具调用记录
- 删除 ToolCallRecorder 的 traceId 管理方法
- 在迁移文档中说明变更

### 10.3 风险：Spring AI 版本兼容性

**影响**：Micrometer Tracing 与 Spring AI 不兼容

**缓解**：
- 使用 Spring Boot 3.4.4 官方推荐的依赖版本
- 在测试环境充分验证

---

## 11. 验收标准

### 11.1 功能验收

- [ ] 所有 HTTP 请求日志包含 `traceId` 和 `spanId`
- [ ] 同一请求的所有日志（Controller/Service/Tool/LLM）traceId 一致
- [ ] 异步场景（线程池）正确传递 traceId
- [ ] 异常日志包含 traceId
- [ ] ToolCallRecorder 正常工作（记录工具调用）

### 11.2 代码验收

- [ ] 删除 ToolCallRecorder 的 traceId 管理方法
- [ ] 删除 RagAgentService 中手动管理 traceId 的代码
- [ ] 添加 Tracer 注入和 MDC 捕获逻辑
- [ ] 更新 logback pattern

### 11.3 测试验收

- [ ] 单元测试覆盖 ToolCallRecorder 的 MDC 集成
- [ ] 集成测试验证异步场景 MDC 传递
- [ ] 端到端测试验证全链路 traceId 一致性

---

## 12. 后续演进

### 12.1 可选增强（未来考虑）

1. **导出到 Jaeger/Tempo**：添加 OTel Collector 配置，实现可视化链路追踪
2. **自定义 span 标签**：为关键操作添加业务标签（如 tenantId、userId）
3. **采样策略**：生产环境可配置采样率（如只记录 10% 的请求）
4. **指标关联**：将 traceId 与 Prometheus 指标关联

### 12.2 迁移路径

```
Phase 1（本期）: 日志中体现 traceId/spanId
    ↓
Phase 2（未来）: 导出到 Jaeger/Tempo，实现可视化
    ↓
Phase 3（未来）: 与 Grafana 集成，统一展示指标 + 链路 + 日志
```

---

## 附录 A：关键代码变更摘要

### A.1 依赖变更

**文件**: `company-rag-bootstrap/pom.xml`

```xml
<!-- 新增 -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

### A.2 日志配置变更

**文件**: `company-rag-bootstrap/src/main/resources/logback-spring.xml`

```xml
<!-- 修改前 -->
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>

<!-- 修改后 -->
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - traceId=%X{traceId} spanId=%X{spanId} - %msg%n</pattern>
```

### A.3 ToolCallRecorder 变更

**文件**: `company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java`

```java
// 删除字段
- private final ThreadLocal<String> traceIdHolder = new ThreadLocal<>();

// 删除方法
- public String generateTraceId()
- public void setTraceId(String traceId)
- public String getTraceId()
- public void clearTraceId()

// 修改方法签名
- public long recordStart(String traceId, String toolName, Map<String, Object> arguments)
+ public long recordStart(String toolName, Map<String, Object> arguments)

- public void recordEnd(String traceId, String toolName, long startTimeMs, String status)
+ public void recordEnd(String toolName, long startTimeMs, String status)

// 方法内部从 MDC 获取 traceId
+ String traceId = MDC.get("traceId");
```

### A.4 RagAgentService 变更

**文件**: `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`

```java
// 新增注入
+ private final Tracer tracer;

public RagAgentService(StreamingAgentExecutor streamingAgentExecutor,
                       ToolCallRecorder recorder,
+                      Tracer tracer) {
    this.streamingAgentExecutor = streamingAgentExecutor;
    this.recorder = recorder;
+   this.tracer = tracer;
}

// 删除手动 traceId 管理
- String traceId = recorder.generateTraceId();
- recorder.setTraceId(traceId);

// 修改日志（不再需要手动传 traceId）
- log.info("[AGENT] traceId={}, userMsg=\"{}\"", traceId, userMessage);
+ log.info("[AGENT] userMsg=\"{}\"", userMessage);

// 异步场景捕获 MDC
+ Map<String, String> mdcContext = MDC.getCopyOfContextMap();
+ Span parentSpan = tracer.currentSpan();

// 子线程恢复 MDC
+ if (mdcContext != null) {
+     MDC.setContextMap(mdcContext);
+ }
```

---

## 附录 B：参考文档

1. [Micrometer Tracing 官方文档](https://docs.micrometer.io/tracing/reference/)
2. [Spring Boot 3.2 Tracing 发布说明](https://spring.io/blog/2023/08/10/observability-with-spring-boot-3-2)
3. [OpenTelemetry Java 文档](https://opentelemetry.io/docs/instrumentation/java/)
4. [Spring AI Micrometer 集成](https://docs.spring.io/spring-ai/reference/api/micrometer.html)

---

**文档结束**
