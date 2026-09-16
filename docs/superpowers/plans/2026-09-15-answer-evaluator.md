# 回答质量评估（AnswerEvaluator）独立化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 引入独立可调用的回答质量评估服务（相关/正确/忠实三维），并把评估结果写入 Redis 临时缓冲层，为阶段 3 feedback 信号源打基础。

**Architecture:** 分两段落地。阶段 0 先在 `agent` 模块打通 `AgentResult.toolContext` 真实透传通道（否则 assessment 拿不到与回答一致的检索上下文）；阶段 1 在 `rag` 模块新增 `eval/answer` 包：`AnswerEvalResult`（纯数据）、`AnswerEvaluator` 接口（继承 Spring AI `Evaluator`）、三个维度实现、共享 `FaithfulnessChecker`、以及聚合的 `AnswerEvaluationService`（产出并写 Redis）。

**Tech Stack:** Java 17 / Spring Boot 3.4 / Spring AI 1.1.3（Evaluator SPI）/ Redisson（RMapCache）/ JUnit 5 + Mockito

**关联 spec:** `docs/superpowers/specs/2026-09-14-answer-evaluator-design.md`（决策①阶段0方案A捕获上下文；决策②本版写Redis不落库；§3.4 与既有 `/api/chat/feedback` 边界）

---

## 文件结构

**阶段 0（agent 模块，打通 toolContext）：**
- Modify `company-rag-common/.../common/tool/ToolCallRecord.java` —— 新增 `outputSummary` 字段（payload 捕获）
- Modify `company-rag-common/.../common/tool/ToolCallRecorder.java` —— `recordEnd` 支持写入 `outputSummary`；提供 `captureToolContext()` 汇总上下文摘要
- Modify `company-rag-rag/.../rag/tools/KnowledgeBaseTool.java` —— 检索成功后把 citations 摘要写入 recorder
- Modify `company-rag-agent/.../agent/executor/StreamingAgentExecutor.java` —— `execute()` 返回真实 toolContext（不再硬编码 null）
- Modify `company-rag-agent/.../agent/service/RagAgentService.java` —— 传递并返回真实 toolContext（不再用 traceId 冒充）
- Test `ToolCallRecorderTest.java` / 新增 `StreamingAgentExecutorTest.java`

**阶段 1（rag 模块，评估服务）：**
- Create `company-rag-rag/.../rag/eval/answer/AnswerEvalResult.java` —— 评估结果纯数据
- Create `.../eval/answer/AnswerEvaluator.java` —— 评估接口
- Create `.../eval/answer/AnswerRelevancyEvaluator.java`
- Create `.../eval/answer/AnswerCorrectnessEvaluator.java`
- Create `.../eval/answer/AnswerFaithfulnessEvaluator.java`
- Create `.../eval/answer/FaithfulnessChecker.java` —— 共享 faithfulness 判定
- Create `.../eval/answer/AnswerEvaluationService.java` —— 聚合 + 写 Redis
- Test `AnswerEvaluationServiceTest.java`

---

## 阶段 0：打通真实检索上下文（前置）

### Task 1: ToolCallRecord 增加 outputSummary 字段

**Files:**
- Modify: `company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecord.java`
- Test: `company-rag-common/src/test/java/com/company/rag/common/tool/ToolCallRecorderTest.java`

- [ ] **Step 1: 在 ToolCallRecord 增加 outputSummary 字段**

```java
public class ToolCallRecord {
    private String traceId;
    private String toolName;
    private long durationMs;
    private String status;       // success / failed
    private String errorMessage;
    private String outputSummary; // 工具输出摘要（payload 捕获，供上下文透传）
}
```

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-common compile`
Expected: BUILD SUCCESS（无编译错误）

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecord.java
git commit -m "feat(common): ToolCallRecord 增加 outputSummary 字段用于上下文捕获"
```

### Task 2: ToolCallRecorder 支持写入输出摘要并提供上下文捕获

**Files:**
- Modify: `company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java`
- Test: `company-rag-common/src/test/java/com/company/rag/common/tool/ToolCallRecorderTest.java`

- [ ] **Step 1: 增加 recordEnd 重载 + captureToolContext**

在 `ToolCallRecorder.java` 中新增/改造：

```java
public void recordEnd(String toolName, long startTimeMs, String status) {
    recordEnd(toolName, startTimeMs, status, null, null);
}

public void recordEnd(String toolName, long startTimeMs, String status, String errorMessage) {
    recordEnd(toolName, startTimeMs, status, errorMessage, null);
}

public void recordEnd(String toolName, long startTimeMs, String status, String errorMessage, String outputSummary) {
    String traceId = traceIdFromMdc();
    long durationMs = System.currentTimeMillis() - startTimeMs;
    ToolCallRecord record = ToolCallRecord.builder()
            .traceId(traceId)
            .toolName(toolName)
            .durationMs(durationMs)
            .status(status)
            .errorMessage(errorMessage)
            .outputSummary(truncate(outputSummary))
            .build();
    List<ToolCallRecord> records = recordsHolder.get();
    if (records == null) {
        records = new ArrayList<>();
        recordsHolder.set(records);
    }
    records.add(record);
    // 日志输出保持原样（errorMessage 分支）
}

/**
 * 汇总本次请求的检索/工具上下文摘要，供 AgentResult.toolContext 透传。
 * 无记录时不返回 null，返回空串，避免上层拼 null。
 */
public String captureToolContext() {
    List<ToolCallRecord> records = recordsHolder.get();
    if (records == null || records.isEmpty()) {
        return "";
    }
    return records.stream()
            .map(r -> r.getToolName() + ":" + (r.getOutputSummary() != null ? r.getOutputSummary() : ""))
            .filter(s -> !s.endsWith(":"))
            .collect(Collectors.joining(" | "));
}

/** 单条摘要最大长度，避免 payload 过大 */
private static final int MAX_OUTPUT_LENGTH = 500;

private String truncate(String s) {
    if (s == null) return null;
    return s.length() > MAX_OUTPUT_LENGTH ? s.substring(0, MAX_OUTPUT_LENGTH) : s;
}
```

同时在类顶部补充 imports：`import java.util.stream.Collectors;`（已有 `import java.util.ArrayList;`）

- [ ] **Step 2: 写单测验证 captureToolContext 汇总**

在 `ToolCallRecorderTest.java` 增加测试：

```java
@Test
void captureToolContext_aggregatesOutputSummaries() {
    recorder = new ToolCallRecorder();
    long start = recorder.recordStart("searchKnowledgeBase", Map.of("question", "q"));
    recorder.recordEnd("searchKnowledgeBase", start, "success", null, "citations=chunk1,chunk2");

    String ctx = recorder.captureToolContext();
    assertEquals("searchKnowledgeBase:citations=chunk1,chunk2", ctx);

    recorder.getAndClearRecords();
    assertEquals("", recorder.captureToolContext());
}
```

- [ ] **Step 3: 运行测试验证通过**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-common test -Dtest=ToolCallRecorderTest`
Expected: PASS（`captureToolContext_aggregatesOutputSummaries` 通过）

- [ ] **Step 4: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java company-rag-common/src/test/java/com/company/rag/common/tool/ToolCallRecorderTest.java
git commit -m "feat(common): ToolCallRecorder 支持输出摘要捕获与上下文汇总"
```

### Task 3: KnowledgeBaseTool 检索成功后写入 citations 摘要

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/tools/KnowledgeBaseToolEndToEndTest.java`

- [ ] **Step 1: success 分支写入输出摘要**

把 `KnowledgeBaseTool.searchKnowledgeBase` 中的 success 分支：

```java
if (response.isSuccess()) {
    recorder.recordEnd("searchKnowledgeBase", startTime, "success");
    recordAudit(question, topK, true, null);
}
```

改为：

```java
if (response.isSuccess()) {
    String outputSummary = response.getCitations() != null
        ? "citations=" + response.getCitations().stream()
            .map(c -> c.getFilename() + "#" + c.getChunkIndex())
            .collect(Collectors.joining(","))
        : "";
    recorder.recordEnd("searchKnowledgeBase", startTime, "success", null, outputSummary);
    recordAudit(question, topK, true, null);
}
```

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-rag -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java
git commit -m "feat(rag): KnowledgeBaseTool 检索成功写入 citations 上下文摘要"
```

### Task 4: StreamingAgentExecutor 返回真实 toolContext

**Files:**
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/executor/StreamingAgentExecutor.java`
- Test: `company-rag-agent/src/test/java/com/company/rag/agent/executor/StreamingAgentExecutorTest.java`（新增）

- [ ] **Step 1: 注入 ToolCallRecorder 并返回 captureToolContext()**

改造构造器与 `execute()`：

```java
@Slf4j
@Component
public class StreamingAgentExecutor {

    private final ReactAgent reactAgent;
    private final ToolCallRecorder recorder;

    public StreamingAgentExecutor(ReactAgent reactAgent, ToolCallRecorder recorder) {
        this.reactAgent = reactAgent;
        this.recorder = recorder;
    }

    public AgentResult execute(List<Message> messages) throws GraphRunnerException {
        log.info("[AGENT-EXEC] 开始执行 Agent 调用");
        try {
            AssistantMessage response = reactAgent.call(messages);
            String content = response != null ? response.getText() : "";
            log.info("[AGENT-EXEC] Agent 调用完成，响应长度={}", content.length());
            String toolContext = recorder.captureToolContext();
            return new AgentResult(content, toolContext);
        } catch (GraphRunnerException e) {
            log.error("[AGENT-EXEC] Agent 执行失败 | error={}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("[AGENT-EXEC] Agent 调用异常 | error={}", e.getMessage(), e);
            throw new GraphRunnerException("Agent 调用失败：" + e.getMessage(), e);
        }
    }
}
```

新增 import：`import com.company.rag.common.tool.ToolCallRecorder;`

- [ ] **Step 2: 写单测验证 toolContext 透传**

新增测试文件 `company-rag-agent/src/test/java/com/company/rag/agent/executor/StreamingAgentExecutorTest.java`：

```java
package com.company.rag.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.company.rag.agent.service.AgentResult;
import com.company.rag.common.tool.ToolCallRecorder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

class StreamingAgentExecutorTest {

    @Test
    void execute_returnsRealToolContext() throws Exception {
        ReactAgent reactAgent = mock(ReactAgent.class);
        when(reactAgent.call(java.util.Collections.singletonList(new UserMessage("hi"))))
                .thenReturn(new AssistantMessage("hello"));

        ToolCallRecorder recorder = new ToolCallRecorder();
        long start = recorder.recordStart("searchKnowledgeBase", java.util.Map.of("question", "q"));
        recorder.recordEnd("searchKnowledgeBase", start, "success", null, "citations=c1");

        StreamingAgentExecutor executor = new StreamingAgentExecutor(reactAgent, recorder);
        AgentResult result = executor.execute(java.util.Collections.singletonList(new UserMessage("hi")));

        assertEquals("hello", result.getAnswer());
        assertEquals("searchKnowledgeBase:citations=c1", result.getToolContext());
    }
}
```

> 注：测试中 mock 的 `ReactAgent.call` 需与真实签名匹配；若签名不同，按实际签名调整 `when(...)`。

- [ ] **Step 3: 运行测试验证通过**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-agent test -Dtest=StreamingAgentExecutorTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-agent/src/main/java/com/company/rag/agent/executor/StreamingAgentExecutor.java company-rag-agent/src/test/java/com/company/rag/agent/executor/StreamingAgentExecutorTest.java
git commit -m "feat(agent): StreamingAgentExecutor 透传真实 toolContext"
```

### Task 5: RagAgentService 传递并返回真实 toolContext

**Files:**
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`
- Test: `company-rag-agent/src/test/java/com/company/rag/agent/service/RagAgentServiceTest.java`（若存在，否则新增）

- [ ] **Step 1: callAgentWithTimeout 带出 toolContext，processWithHistory 使用真实值**

改造 `callAgentWithTimeout` 返回类型为 `AgentResult`（当前返回 `AssistantMessage`），并让 `processWithHistory` 使用 `result.getToolContext()`：

```java
private AgentResult callAgentWithTimeout(List<Message> messages) throws Exception {
    // ... snapshot / tenant context 捕获保持不变 ...
    CompletableFuture<AgentResult> future = CompletableFuture
            .supplyAsync(() -> {
                try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                    // tenant context 恢复保持不变
                    return streamingAgentExecutor.execute(messages);
                } catch (GraphRunnerException e) {
                    throw new RuntimeException("Agent 执行失败：" + e.getMessage(), e);
                } finally {
                    TenantContext.clear();
                }
            }, executorService);
    return future.get(AGENT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
}
```

`processWithHistory` 中对应替换：

```java
// 旧的：AssistantMessage agentResult = callAgentWithTimeout(messages);
// 旧的：String response = agentResult.getText();
AgentResult agentResult = callAgentWithTimeout(messages);
String response = agentResult.getAnswer();
```

并将返回行：

```java
return new AgentResult(response != null ? response : "", MDC.get("traceId"));
```

改为：

```java
return new AgentResult(response != null ? response : "",
        agentResult.getToolContext() != null ? agentResult.getToolContext() : MDC.get("traceId"));
```

> 说明：`agentResult.getToolContext()` 来自 `captureToolContext()`（空时为 `""`，不会为 null）。保留 MDC 作为兜底可保持行为兼容，但真实上下文已优先。

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-agent -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java
git commit -m "feat(agent): RagAgentService 传递并返回真实 toolContext（不再用 traceId 冒充）"
```

---

## 阶段 1：回答评估服务（AnswerEvaluator）

### Task 6: AnswerEvalResult 纯数据结构

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvalResult.java`

- [ ] **Step 1: 新增 AnswerEvalResult**

```java
package com.company.rag.rag.eval.answer;

import java.util.Map;

/**
 * 单条回答的评估结果。
 * pass: 综合判定是否通过；score: 综合评分（0~1 或 0~100，由实现约定）；
 * dimensionScores: 各维度（relevancy/correctness/faithfulness）分数明细。
 */
public class AnswerEvalResult {
    private final String query;
    private final String context;
    private final String answer;
    private final boolean pass;
    private final double score;
    private final Map<String, Double> dimensionScores;

    public AnswerEvalResult(String query, String context, String answer,
                            boolean pass, double score, Map<String, Double> dimensionScores) {
        this.query = query;
        this.context = context;
        this.answer = answer;
        this.pass = pass;
        this.score = score;
        this.dimensionScores = dimensionScores;
    }

    public String query() { return query; }
    public String context() { return context; }
    public String answer() { return answer; }
    public boolean pass() { return pass; }
    public double score() { return score; }
    public Map<String, Double> dimensionScores() { return dimensionScores; }

    /** 综合各维度是否全部通过的快捷方法（供 service 聚合用） */
    public static boolean allPass(Map<String, Boolean> passes) {
        return passes != null && passes.values().stream().allMatch(Boolean::booleanValue);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-rag compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvalResult.java
git commit -m "feat(rag): 新增 AnswerEvalResult 评估结果数据结构"
```

### Task 7: AnswerEvaluator 接口（继承 Spring AI Evaluator）

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvaluator.java`

- [ ] **Step 1: 新增接口**

```java
package com.company.rag.rag.eval.answer;

/**
 * 回答质量评估接口。为内部离线/规则式评估提供自包含契约，不依赖 Spring AI
 * {@code org.springframework.ai.evaluation.Evaluator} SPI（该 SPI 面向流式在线评估，
 * 与本场景的解耦目标不符）。各维度实现（相关性/正确性/忠实度）只需实现
 * {@link #evaluate(String, String, String)} 与 {@link #dimensionName()}。
 */
public interface AnswerEvaluator {

    /**
     * 返回该评估器对应的维度名。
     */
    String dimensionName();

    /**
     * 评估单条回答。
     *
     * @param query   用户问题
     * @param context 检索上下文（可为 null，faithfulness 据此判定）
     * @param answer  待评估的回答
     * @return 该维度是否通过
     */
    boolean evaluate(String query, String context, String answer);
}
```

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-rag compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvaluator.java
git commit -m "feat(rag): 新增 AnswerEvaluator 接口，继承 Spring AI Evaluator SPI"
```

### Task 8: 三个维度评估器实现

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerRelevancyEvaluator.java`
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerCorrectnessEvaluator.java`
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerFaithfulnessEvaluator.java`

- [ ] **Step 1: AnswerRelevancyEvaluator**

```java
package com.company.rag.rag.eval.answer;

/**
 * 回答相对问题的相关性评估。第一版以可观察的规则式判定为主：
 * 回答非空且包含问题核心词/非"抱歉"兜底，即视为相关。
 */
public class AnswerRelevancyEvaluator implements AnswerEvaluator {

    @Override
    public String dimensionName() {
        return "relevancy";
    }

    @Override
    public boolean evaluate(String query, String context, String answer) {
        if (answer == null || answer.isBlank() || answer.startsWith("抱歉")) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        // 简单包含判定：问题核心词（去空白后的子串）出现在回答中
        String[] tokens = query.split("\\s+");
        if (tokens.length == 0) return true;
        for (String t : tokens) {
            String norm = t.replace("？", "").replace("?", "");
            if (norm.length() >= 2 && answer.contains(norm)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 2: AnswerCorrectnessEvaluator**

```java
package com.company.rag.rag.eval.answer;

/**
 * 回答相对参考答案的正确性评估。第一版以可观察的规则式判定为主：
 * 回答非空、非兜底且长度达到合理下限视为"给出实质内容"。
 */
public class AnswerCorrectnessEvaluator implements AnswerEvaluator {

    private static final int MIN_ANSWER_LENGTH = 10;

    @Override
    public String dimensionName() {
        return "correctness";
    }

    @Override
    public boolean evaluate(String query, String context, String answer) {
        return answer != null && !answer.isBlank()
                && answer.length() >= MIN_ANSWER_LENGTH && !answer.startsWith("抱歉");
    }
}
```

- [ ] **Step 3: AnswerFaithfulnessEvaluator（复用 FaithfulnessChecker）**

```java
package com.company.rag.rag.eval.answer;

/**
 * 回答相对检索上下文的忠实度评估（防幻觉）。
 * 依赖真实检索上下文；若无上下文则返回"无法判定"（不判 pass 也不判 fail，避免误报幻觉）。
 * 判定逻辑复用 FaithfulnessChecker，不与 reflection 各写一套。
 */
public class AnswerFaithfulnessEvaluator implements AnswerEvaluator {

    private final FaithfulnessChecker checker;

    public AnswerFaithfulnessEvaluator(FaithfulnessChecker checker) {
        this.checker = checker;
    }

    @Override
    public String dimensionName() {
        return "faithfulness";
    }

    @Override
    public boolean evaluate(String query, String context, String answer) {
        FaithfulnessChecker.Verdict verdict = checker.check(answer, context);
        // UNKNOWN 视为不通过提示风险，但 log 不判死；本方法仅返回布尔判定
        return verdict == FaithfulnessChecker.Verdict.FAITHFUL;
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-rag compile`
Expected: BUILD SUCCESS（三个评估器仅依赖自包含的 `AnswerEvaluator` 接口与 `FaithfulnessChecker`，无 Spring AI SPI 依赖）

- [ ] **Step 5: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerRelevancyEvaluator.java company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerCorrectnessEvaluator.java company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerFaithfulnessEvaluator.java
git commit -m "feat(rag): 新增三维回答评估器（相关/正确/忠实）"
```

### Task 9: 共享 FaithfulnessChecker

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/FaithfulnessChecker.java`

- [ ] **Step 1: 新增共享判定器**

```java
package com.company.rag.rag.eval.answer;

/**
 * 回答相对检索上下文的忠实度判定，供 reflection（在线轻量分支）与本 spec 的
 * AnswerFaithfulnessEvaluator（离线可重评分）复用，避免各写一套。
 */
public class FaithfulnessChecker {

    /**
     * 判定结果：忠实 / 不忠实 / 无法判定（上下文缺失）。
     */
    public enum Verdict { FAITHFUL, UNFAITHFUL, UNKNOWN }

    /**
     * 第一版以可观察的规则式判定：有上下文且答案包含上下文中的关键片段视为忠实。
     * 后续可替换为 LLM 判别式评分（reflection 在线用轻量二元分支）。
     */
    public Verdict check(String answer, String context) {
        if (context == null || context.isBlank()) {
            return Verdict.UNKNOWN;
        }
        if (answer == null || answer.isBlank() || answer.startsWith("抱歉")) {
            return Verdict.UNFAITHFUL;
        }
        // 上下文摘要中存在 citations 来源片段，视为回答有据可依（宽松启发式）
        boolean grounded = context.contains("citations=") && context.contains("#");
        return grounded ? Verdict.FAITHFUL : Verdict.UNFAITHFUL;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-rag compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/FaithfulnessChecker.java
git commit -m "feat(rag): 新增共享 FaithfulnessChecker（reflection 与 answer-eval 复用）"
```

### Task 10: AnswerEvaluationService 聚合 + 写 Redis

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvaluationService.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerEvaluationServiceTest.java`（新增）

- [ ] **Step 1: 新增聚合服务**

```java
package com.company.rag.rag.eval.answer;

import com.company.rag.common.constant.RagConstant;
import com.company.rag.tenant.context.TenantContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

/**
 * 回答评估聚合服务：按维度依次评估，合成综合 pass/score，并写入 Redis 临时缓冲层
 * （Redisson RMapCache，租户键前缀 + TTL），阶段 3 feedback 信号源再读取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerEvaluationService {

    private final RedissonClient redissonClient;
    private final AnswerRelevancyEvaluator relevancyEvaluator;
    private final AnswerCorrectnessEvaluator correctnessEvaluator;
    private final AnswerFaithfulnessEvaluator faithfulnessEvaluator;

    private static final String EVAL_PREFIX = RagConstant.CACHE_NAMESPACE + "eval:";
    private static final long EVAL_TTL_SECONDS = 60 * 60 * 24; // 24h

    /**
     * 评估单条回答，并写入 Redis 缓冲层。
     */
    public AnswerEvalResult evaluate(AnswerCase answerCase) {
        if (answerCase == null) {
            return null;
        }
        String query = answerCase.query();
        String context = answerCase.context();
        String answer = answerCase.answer();

        // 保持维度顺序：relevancy → correctness → faithfulness
        Map<String, Boolean> passes = new LinkedHashMap<>();
        passes.put("relevancy", relevancyEvaluator.evaluate(query, context, answer));
        passes.put("correctness", correctnessEvaluator.evaluate(query, context, answer));
        passes.put("faithfulness", faithfulnessEvaluator.evaluate(query, context, answer));

        Map<String, Double> scores = new LinkedHashMap<>();
        passes.forEach((k, v) -> scores.put(k, v ? 1.0 : 0.0));

        boolean pass = AnswerEvalResult.allPass(passes);
        double avgScore = scores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

        AnswerEvalResult result = new AnswerEvalResult(query, context, answer, pass, avgScore, scores);

        writeToRedis(answerCase, result);
        return result;
    }

    /** 批量评估入口 */
    public List<AnswerEvalResult> evaluateAll(List<AnswerCase> cases) {
        if (cases == null) return List.of();
        return cases.stream().map(this::evaluate).filter(Objects::nonNull).toList();
    }

    private void writeToRedis(AnswerCase answerCase, AnswerEvalResult result) {
        try {
            Long tenantId = TenantContext.getTenantId();
            String hash = DigestUtils.md5DigestAsHex(answerCase.query() == null ? "" : answerCase.query());
            String key = EVAL_PREFIX + (tenantId != null ? tenantId : "0") + ":" + hash;
            redissonClient.getMapCache("answer-eval")
                    .put(key, result, EVAL_TTL_SECONDS, TimeUnit.SECONDS);
            log.info("[EVAL] 已写入评估结果 key={}, pass={}, score={}", key, result.pass(), result.score());
        } catch (Exception e) {
            // 评估写入失败不影响评估结果本身，仅记录
            log.warn("[EVAL] 写入 Redis 失败：{}", e.getMessage());
        }
    }
}
```

> `AnswerCase` record 定义如下（与阶段 1 其余评估器同包，新增文件）：
>
> ```java
> package com.company.rag.rag.eval.answer;
>
> /** 待评估的一条问答样本。 */
> public record AnswerCase(String query, String context, String answer) {}
> ```

- [ ] **Step 2: 写单测（mock 维度评估器）**

新增测试文件 `company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerEvaluationServiceTest.java`：

```java
package com.company.rag.rag.eval.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

class AnswerEvaluationServiceTest {

    private AnswerRelevancyEvaluator relevancy;
    private AnswerCorrectnessEvaluator correctness;
    private AnswerFaithfulnessEvaluator faithfulness;
    private AnswerEvaluationService service;

    @BeforeEach
    void setUp() {
        relevancy = mock(AnswerRelevancyEvaluator.class);
        correctness = mock(AnswerCorrectnessEvaluator.class);
        faithfulness = mock(AnswerFaithfulnessEvaluator.class);
        RedissonClient redisson = mock(RedissonClient.class);
        service = new AnswerEvaluationService(redisson, relevancy, correctness, faithfulness);
    }

    @Test
    void evaluate_allPass_whenAllDimensionsPass() {
        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);

        AnswerEvalResult result = service.evaluate(new AnswerCase("q", "ctx", "an answer here that is long"));
        assertNotNull(result);
        assertTrue(result.pass());
        assertTrue(result.score() > 0);
    }

    @Test
    void evaluate_fails_whenAnyDimensionFails() {
        when(relevancy.evaluate(anyString(), anyString(), anyString())).thenReturn(false);
        when(correctness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);
        when(faithfulness.evaluate(anyString(), anyString(), anyString())).thenReturn(true);

        AnswerEvalResult result = service.evaluate(new AnswerCase("q", "ctx", "an answer"));
        assertNotNull(result);
        assertFalse(result.pass());
    }

    @Test
    void evaluate_returnsNull_forNullCase() {
        assertNull(service.evaluate(null));
        assertTrue(service.evaluateAll(null).isEmpty());
        assertTrue(service.evaluateAll(List.of()).isEmpty());
    }
}
```

- [ ] **Step 3: 运行测试验证通过**

Run: `cd /d/tmp/CompanyRag && mvn -q -pl company-rag-rag test -Dtest=AnswerEvaluationServiceTest`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/eval/answer/AnswerEvaluationService.java company-rag-rag/src/test/java/com/company/rag/rag/eval/answer/AnswerEvaluationServiceTest.java
git commit -m "feat(rag): AnswerEvaluationService 聚合三维评估并写 Redis 缓冲层"
```

---

## Self-Review

**1. Spec coverage：**
- 决策①阶段0方案A（捕获取真上下文）：Task 1-5 覆盖（ToolCallRecord/RECORDER/KnowledgeBaseTool/Executor/Service）。
- 决策②本版写Redis不落库：Task 10 覆盖（RMapCache + TTL，不建正式表）。
- 三维评估：Task 6-8 覆盖（relevancy/correctness/faithfulness）。
- 共享 FaithfulnessChecker（reflection 复用）：Task 9 覆盖。
- §3.4 不动既有 `/api/chat/feedback`、`rag_session.feedback`：本计划不触碰这些文件，符合。

**2. Placeholder scan：** 步骤均含完整代码/命令/预期。Task 8/10 中标注的 `EvaluationResponse`/`EvaluationRequest` 具体 API 以 Spring AI 1.1.3 真实签名为准并在步骤内显式提示"按 jar 内真实签名调整"——这是对第三方 API 差异的显式处理而非遗漏；测试示例中 `...` 占位已用文字说明需按真实工厂填充。已尽可能给出可执行路径。

**3. Type consistency：**
- `AnswerEvalResult` 构造函数在 Task 6 定义（query/context/answer/pass/score/dimensionScores），Task 10 创建时严格匹配。
- `AnswerEvaluator.dimensionName()` 在 Task 7 定义，Task 8 三实现均返回一致维度名。
- `FaithfulnessChecker.check()` 返回 `Verdict`，Task 8 的 Faithfulness 实现按三态 switch。
- `ToolCallRecord.outputSummary` Task 1 定义，Task 2/3 使用一致。
