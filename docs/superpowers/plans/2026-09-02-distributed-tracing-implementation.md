# 分布式追踪实施计划 — CompanyRag

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为项目引入 Micrometer Tracing + OpenTelemetry 分布式追踪，让所有日志自动携带 `traceId`/`spanId`，贯穿 Web → Agent → Tool → LLM 全链路，并移除 `ToolCallRecorder`/`RagAgentService` 中手动管理 traceId 的逻辑。

**Architecture:** 引入 `micrometer-tracing-bridge-otel` 由框架自动生成/注入 traceId 到 MDC；修改 logback pattern 输出 `%X{traceId}`/`%X{spanId}`；在 `RagAgentService` 异步线程池场景捕获并恢复 MDC 上下文（沿用 `TenantContext` 的传递模式）；`ToolCallRecorder` 从 `MDC.get("traceId")` 读取 traceId，精简为纯工具调用记录组件。本期不配置外部 exporter。

**Tech Stack:** Spring Boot 3.4.4、Spring AI 1.0.4、Micrometer Tracing、OpenTelemetry、SLF4J MDC、JUnit5 / Mockito。

---

## 文件结构

| 文件 | 类型 | 职责 |
|------|------|------|
| `company-rag-bootstrap/pom.xml` | 修改 | 添加 Micrometer Tracing + OTel 依赖 |
| `company-rag-bootstrap/src/main/resources/logback-spring.xml` | 修改 | pattern 增加 `%X{traceId}`/`%X{spanId}` |
| `company-rag-common/.../tool/ToolCallRecorder.java` | 重写 | 删除 traceId 管理方法，从 MDC 读取 traceId |
| `company-rag-common/.../tool/ToolCallRecord.java` | 不变 | 工具调用记录实体 |
| `company-rag-rag/.../tools/KnowledgeBaseTool.java` | 修改 | 适配 recordStart/recordEnd 新签名 |
| `company-rag-agent/.../service/RagAgentService.java` | 修改 | 删除手动 traceId 管理，异步 MDC 传递 |
| `company-rag-common/.../tool/ToolCallRecorderTest.java` | 修改 | 适配新签名，覆盖 MDC 集成 |
| `company-rag-rag/.../tools/KnowledgeBaseToolEndToEndTest.java` | 修改 | 适配新签名（verify check） |

---

## Task 1: 添加 Micrometer Tracing + OpenTelemetry 依赖

**Files:**
- Modify: `company-rag-bootstrap/pom.xml`

- [x] **Step 1: 在 `pom.xml` 中添加依赖**

在 `company-rag-bootstrap/pom.xml` 的 `<dependencies>` 中，紧邻 `micrometer-registry-prometheus` 依赖（约第 73 行）之后加入：

```xml
        <!-- Micrometer Tracing + OpenTelemetry（分布式链路追踪） -->
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-tracing-bridge-otel</artifactId>
        </dependency>
        <dependency>
            <groupId>io.opentelemetry</groupId>
            <artifactId>opentelemetry-exporter-otlp</artifactId>
        </dependency>
```

- [x] **Step 2: 编译验证依赖可解析**

Run: `mvn -q -pl company-rag-bootstrap -am -DskipTests compile`
Expected: `BUILD SUCCESS`（版本由 Spring Boot 3.4.4 BOM 管理）

- [x] **Step 3: 提交**

```bash
git add company-rag-bootstrap/pom.xml
git commit -m "feat(tracing): 引入 micrometer-tracing-bridge-otel 依赖"
```

---

## Task 2: 修改 logback pattern 输出 traceId/spanId

**Files:**
- Modify: `company-rag-bootstrap/src/main/resources/logback-spring.xml`

- [x] **Step 1: 替换三处 pattern**

将文件中 3 个 appender（CONSOLE、FILE、ERROR_FILE）的 `<pattern>` 从：
```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
```
统一替换为：
```xml
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - traceId=%X{traceId} spanId=%X{spanId} - %msg%n</pattern>
```

- [x] **Step 2（补充，本次运行时验证发现）: 启用 MDC 关联**

仅加 logback pattern 不会自动生效 —— traceId 需由 Micrometer Tracing 写入 MDC。必须在 `application.yml` 开启 `management.tracing.enabled=true`，否则 `%X{traceId}` 恒为空：

```yaml
management:
  tracing:
    enabled: true
```

```bash
git add company-rag-bootstrap/src/main/resources/application.yml
git commit -m "feat(tracing): 启用 management.tracing.enabled 注入 traceId 到 MDC"
```

- [x] **Step 3: 提交**

```bash
git add company-rag-bootstrap/src/main/resources/logback-spring.xml
git commit -m "feat(tracing): logback pattern 输出 traceId/spanId"
```

---

## Task 3: 重写 ToolCallRecorder（从 MDC 读取 traceId）

**Files:**
- Modify: `company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java`
- Test: `company-rag-common/src/test/java/com/company/rag/common/tool/ToolCallRecorderTest.java`

> 说明：删除 `generateTraceId/setTraceId/getTraceId/clearTraceId`，`recordStart/recordEnd` 改为从 `MDC.get("traceId")` 读取；`getAndClearRecords()` 去掉 traceId 参数。

- [x] **Step 1: 更新单元测试（红）**

将 `ToolCallRecorderTest.java` 的测试改为针对新签名与 MDC 集成，完整内容如下：

```java
package com.company.rag.common.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallRecorderTest {

    private ToolCallRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ToolCallRecorder();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void recordStart_getsTraceIdFromMdc() {
        MDC.put("traceId", "trace-1");
        long start = recorder.recordStart("testTool", Map.of("key", "value"));
        assertTrue(start > 0);
    }

    @Test
    void recordStart_withNullArgs_returnsPositiveStart() {
        MDC.put("traceId", "trace-1");
        long start = recorder.recordStart("testTool", null);
        assertTrue(start > 0);
    }

    @Test
    void recordEnd_aggregatesRecordWithMdcTraceId() {
        MDC.put("traceId", "trace-1");
        long start = recorder.recordStart("testTool", Map.of("k", "v"));
        recorder.recordEnd("testTool", start, "success");

        List<ToolCallRecord> records = recorder.getAndClearRecords();
        assertEquals(1, records.size());
        ToolCallRecord rec = records.get(0);
        assertEquals("trace-1", rec.getTraceId());
        assertEquals("testTool", rec.getToolName());
        assertEquals("success", rec.getStatus());
    }

    @Test
    void recordEnd_withErrorMessage_recordsWarn() {
        MDC.put("traceId", "trace-2");
        long start = recorder.recordStart("testTool", null);
        recorder.recordEnd("testTool", start, "failed", "boom");

        List<ToolCallRecord> records = recorder.getAndClearRecords();
        assertEquals(1, records.size());
        assertEquals("boom", records.get(0).getErrorMessage());
        assertEquals("failed", records.get(0).getStatus());
    }

    @Test
    void getAndClearRecords_clearsAfterRetrieve() {
        MDC.put("traceId", "trace-3");
        long start = recorder.recordStart("testTool", null);
        recorder.recordEnd("testTool", start, "success");

        List<ToolCallRecord> first = recorder.getAndClearRecords();
        assertEquals(1, first.size());
        assertTrue(recorder.getAndClearRecords().isEmpty());
    }

    @Test
    void getAndClearRecords_noMdcReturnsEmpty() {
        assertTrue(recorder.getAndClearRecords().isEmpty());
    }
}
```

- [x] **Step 2: 运行测试确认失败（红）**

Run: `mvn -q -pl company-rag-common -Dtest=ToolCallRecorderTest test`
Expected: 编译失败（`ToolCallRecorder` 无无参构造、`getAndClearRecords()` 无参、`recorder.recordStart(String, Map)` 无法解析）

- [x] **Step 3: 重写 ToolCallRecorder**

用以下内容整体替换 `ToolCallRecorder.java`：

```java
package com.company.rag.common.tool;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具调用记录器（通用组件）
 * traceId 由 Micrometer Tracing 自动写入 MDC，此处仅负责记录工具调用的耗时与状态
 */
@Slf4j
@Component
public class ToolCallRecorder {

    private static final int MAX_INPUT_LENGTH = 50;

    private final ThreadLocal<List<ToolCallRecord>> recordsHolder = new ThreadLocal<>();

    /**
     * 记录工具调用开始，traceId 从 MDC 读取
     * @return 开始时间戳（毫秒）
     */
    public long recordStart(String toolName, Map<String, Object> arguments) {
        String traceId = traceIdFromMdc();
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
        String traceId = traceIdFromMdc();
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

    /**
     * 获取并清除本次请求的所有工具调用记录
     */
    public List<ToolCallRecord> getAndClearRecords() {
        List<ToolCallRecord> records = recordsHolder.get();
        recordsHolder.remove();
        if (records == null) {
            return List.of();
        }
        return records;
    }

    /**
     * 从 MDC 读取当前 traceId，获取不到时返回空串（避免拼 null）
     */
    private String traceIdFromMdc() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "";
    }
}
```

- [x] **Step 4: 运行测试确认通过（绿）**

Run: `mvn -q -pl company-rag-common -Dtest=ToolCallRecorderTest test`
Expected: `BUILD SUCCESS`，全部测试通过

- [x] **Step 5: 提交**

```bash
git add company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java company-rag-common/src/test/java/com/company/rag/common/tool/ToolCallRecorderTest.java
git commit -m "refactor(tracing): ToolCallRecorder 改为从 MDC 读取 traceId"
```

---

## Task 4: 适配 KnowledgeBaseTool 与 EndToEndTest

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/tools/KnowledgeBaseToolEndToEndTest.java`

> 说明：主代码 `KnowledgeBaseTool.java:63-95` 需删除 `recorder.getTraceId()`，并将 `recordStart/recordEnd` 调用改为无 traceId 的新签名。`KnowledgeBaseToolEndToEndTest.java` 的 verify 已是新签名（`recordStart("searchKnowledgeBase", any(), any())`），本任务需保证编译一致。

- [x] **Step 1: 更新 `searchKnowledgeBase` 方法**

将 `KnowledgeBaseTool.java` 方法体内（第 56-96 行）的工具调用记录逻辑替换：

**删除/替换为**（`String traceId = recorder.getTraceId();` 整行删除；所有 `recordStart(traceId, ...)` → `recordStart(...)`，`recordEnd(traceId, ...)` → `recordEnd(...)`）：

```java
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("question", question);
        if (topK != null) {
            args.put("topK", topK);
        }
        long startTime = recorder.recordStart("searchKnowledgeBase", args);

        try {
            // 参数校验
            if (question == null || question.trim().isEmpty()) {
                recorder.recordEnd("searchKnowledgeBase", startTime, "failed");
                return KnowledgeBaseResult.failed("问题不能为空");
            }

            // 调用 RAG 引擎（混合检索 + Rerank）
            int effectiveTopK = (topK == null || topK <= 0) ? 5 : topK;
            RagQuery query = new RagQuery();
            query.setTenantId(TenantContext.getTenantId());
            query.setQuery(question);
            query.setTopK(effectiveTopK);
            RagResult result = ragSearchService.search(query);

            // 转换为 KnowledgeBaseResult
            KnowledgeBaseResult response = convertToKnowledgeBaseResult(result);

            if (response.isSuccess()) {
                recorder.recordEnd("searchKnowledgeBase", startTime, "success");
            } else {
                recorder.recordEnd("searchKnowledgeBase", startTime, "failed");
            }

            return response;

        } catch (Exception e) {
            log.error("知识库工具调用失败：question={}, err={}", question, e.getMessage());
            recorder.recordEnd("searchKnowledgeBase", startTime, "failed", e.getMessage());
            return KnowledgeBaseResult.failed("工具调用失败：" + e.getMessage());
        }
```

- [x] **Step 2: 检查 EndToEndTest 的 verify 新签名**

读取 `KnowledgeBaseToolEndToEndTest.java` 第 60-120 行，确认 verify 调用为 `recordStart("searchKnowledgeBase", any(), any())` 与 `recordEnd("searchKnowledgeBase", "success"/"failed", any(), any())`。若存在任何旧 4 参数 `recordStart`/`recordEnd` 调用，也一并改为新签名。

- [x] **Step 3: 编译 + 运行模块测试（绿）**

Run: `mvn -q -pl company-rag-rag -am -Dtest=KnowledgeBaseToolEndToEndTest,KnowledgeBaseToolTest test`
Expected: `BUILD SUCCESS`，测试通过（会连带编译 company-rag-common）

- [x] **Step 4: 提交**

```bash
git add company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java company-rag-rag/src/test/java/com/company/rag/rag/tools/KnowledgeBaseToolEndToEndTest.java
git commit -m "refactor(tracing): KnowledgeBaseTool 适配 ToolCallRecorder 新签名"
```

---

## Task 5: 更新 RagAgentService（去手动 traceId + 异步 MDC 传递）

**Files:**
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`

> 说明：删除 `recorder.generateTraceId()/setTraceId()/clearTraceId()` 调用与 `[AGENT] traceId=` 手动输出；在 `callAgentWithTimeout` 异步提交前捕获 MDC，子线程恢复。沿用 `TenantContext` 传递模式，并用 `MDC.clear()` 兜底清理线程池复用污染。

- [x] **Step 1: 增加 import**

在 `RagAgentService.java` import 区加入：

```java
import org.slf4j.MDC;
import java.util.Map;
```

- [x] **Step 2: 重写 `processWithHistory` 的工具记录部分**

将 `processWithHistory` 方法（第 86-130 行）改为：

```java
    public AgentResult processWithHistory(List<Message> history, String userMessage) {
        long requestStart = System.currentTimeMillis();

        log.info("[AGENT] userMsg=\"{}\", historySize={}",
                userMessage, history != null ? history.size() : 0);

        try {
            // 构建消息列表
            List<Message> messages = new ArrayList<>();
            if (history != null && !history.isEmpty()) {
                messages.addAll(history);
            }
            messages.add(new UserMessage(userMessage));

            // 使用 StreamingAgentExecutor 处理请求，带超时保护
            AssistantMessage agentResult = callAgentWithTimeout(messages);

            String response = agentResult.getText();

            // 聚合工具调用记录，输出结构化日志
            long totalMs = System.currentTimeMillis() - requestStart;
            List<ToolCallRecord> records = recorder.getAndClearRecords();
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

- [x] **Step 3: 重写 `callAgentWithTimeout` 实现 MDC 传递**

将 `callAgentWithTimeout` 方法（第 142-199 行）中的异步提交部分改为在提交前捕获 MDC，在子线程恢复并兜底清理：

```java
    private AssistantMessage callAgentWithTimeout(List<Message> messages) throws GraphRunnerException, Exception {
        try {
            // 捕获当前线程的 MDC（含 traceId/spanId）与租户上下文，因 ThreadLocal 不自动传给子线程
            Map<String, String> mdcContext = MDC.getCopyOfContextMap();
            String tenantSchema = TenantContext.getSchema();
            Long tenantId = TenantContext.getTenantId();
            Long userId = TenantContext.getUserId();
            String tenantCode = TenantContext.getTenantCode();
            String sessionId = TenantContext.getSessionId();

            CompletableFuture<AssistantMessage> future = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            // 恢复 MDC 与租户上下文
                            if (mdcContext != null) {
                                MDC.setContextMap(mdcContext);
                            }
                            if (tenantSchema != null) {
                                TenantContext.setSchema(tenantSchema);
                            }
                            if (tenantId != null) {
                                TenantContext.setTenantId(tenantId);
                            }
                            if (userId != null) {
                                TenantContext.setUserId(userId);
                            }
                            if (tenantCode != null) {
                                TenantContext.setTenantCode(tenantCode);
                            }
                            if (sessionId != null) {
                                TenantContext.setSessionId(sessionId);
                            }

                            AgentResult result = streamingAgentExecutor.execute(messages);
                            return new AssistantMessage(result.getAnswer());
                        } catch (GraphRunnerException e) {
                            throw new RuntimeException("Agent 执行失败：" + e.getMessage(), e);
                        } finally {
                            // 清理线程上下文，避免线程池复用时的数据污染
                            MDC.clear();
                            TenantContext.clear();
                        }
                    }, executorService);

            return future.get(AGENT_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        } catch (TimeoutException e) {
            log.error("[AGENT] 调用超时：timeout={} minutes，请简化问题或减少工具调用", AGENT_TIMEOUT_MINUTES);
            throw new TimeoutException(String.format("Agent 调用超时：%d 分钟，可能原因：1) LLM 响应过慢 2) 工具调用次数过多 3) ReAct 循环",
                    AGENT_TIMEOUT_MINUTES));
        } catch (Exception e) {
            if (e.getCause() instanceof GraphRunnerException) {
                throw (GraphRunnerException) e.getCause();
            }
            log.error("[AGENT] 调用失败：error={}", e.getMessage(), e);
            throw e;
        }
    }
```

> 注意：`MDC.get("traceId")` 在 `processWithHistory` 主线程中调用（该线程由 Spring Web 持有了 trace）。若主线程 traceId 恰为空串，`AgentResult` 的 traceId 字段即为空——不影响功能（该字段仅作返回给前端的标识）。

- [x] **Step 4: 编译验证**

Run: `mvn -q -pl company-rag-agent -am -DskipTests compile`
Expected: `BUILD SUCCESS`

- [x] **Step 5: 提交**

```bash
git add company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java
git commit -m "feat(tracing): RagAgentService 移除手动 traceId 并传递异步 MDC"
```

---

## Task 6: 整体构建与回归验证

**Files:**
- None（仅验证）

- [x] **Step 1: 编译全模块（跳过测试）**

Run: `mvn -q -DskipTests compile`
Expected: `BUILD SUCCESS`（验证所有模块适配新签名，无编译残留）

- [x] **Step 2: 运行受影响模块的测试**

Run: `mvn -q -pl company-rag-common,company-rag-rag,company-rag-agent -am -Dtest='ToolCallRecorderTest,KnowledgeBaseToolTest,KnowledgeBaseToolEndToEndTest,DownloadToolTest,DatabaseQueryToolTest,ExecuteToolTest' test`
Expected: `BUILD SUCCESS`，所有测试通过

- [x] **Step 3: 手动启动验证日志含 traceId（如有条件）**

Run: `mvn -pl company-rag-bootstrap spring-boot:run`
然后 `curl -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -H "X-Tenant-Id: 1" -d '{"query":"测试"}'`
Expected: 控制台日志显示 `traceId=<hex> spanId=<hex> - ...`，且同一请求多条日志 traceId 相同。

---

## 自检核对

- **Spec 覆盖**：
  - 依赖添加 → Task 1
  - logback pattern → Task 2
  - ToolCallRecorder 简化（删除 traceId 管理、从 MDC 读取）→ Task 3
  - KnowledgeBaseTool 适配 → Task 4
  - RagAgentService 去手动 traceId + 异步 MDC 传递 → Task 5
  - 验收/构建验证 → Task 6
- **无占位符**：所有代码块均为完整实现，无 TBD/TODO。
- **类型一致性**：`recordStart(String toolName, Map)`、`recordEnd(String toolName, long, String[, String])`、`getAndClearRecords()` 在 Task 3/4/5 保持一致。