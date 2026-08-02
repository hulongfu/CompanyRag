# Agent 第二阶段增强实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完善 Agent 模式的工具描述、可解释性日志和缓存性能

**Architecture:** 三个独立模块并行推进：①工具 description 文本修改 ②ToolCallRecorder 增强 + RagAgentService 日志聚合 ③Caffeine 缓存配置 + @Cacheable 注解

**Tech Stack:** Spring Boot 3.4.4, Spring AI 1.0.4, Caffeine, Redisson(已有)

**关联设计:** `docs/superpowers/specs/2026-08-02-agent-phase2-enhancement-design.md`

---

## 文件结构

### 新增文件
- `company-rag-rag/src/main/java/com/company/rag/rag/config/RagCacheConfig.java` - Caffeine 缓存配置

### 修改文件
- `company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java` - @Tool description 增强 + @Cacheable
- `company-rag-agent/src/main/java/com/company/rag/agent/tool/DatabaseQueryTool.java` - @Tool description 增强
- `company-rag-agent/src/main/java/com/company/rag/agent/tool/CodeSearchTool.java` - @Tool description 增强
- `company-rag-agent/src/main/java/com/company/rag/agent/tool/ApiDocTool.java` - @Tool description 增强
- `company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java` - traceId、耗时、记录聚合
- `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java` - traceId 生成、日志输出
- `company-rag-rag/pom.xml` - 添加 spring-boot-starter-cache + caffeine

### 测试文件
- `company-rag-common/src/test/java/com/company/rag/common/tool/ToolCallRecorderTest.java` - 新增功能测试

---

## Task 1: 完善 4 个工具的 @Tool description

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java:35-40`
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/tool/DatabaseQueryTool.java:78`
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/tool/CodeSearchTool.java:83`
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/tool/ApiDocTool.java:65`

- [ ] **Step 1: 修改 KnowledgeBaseTool 的 @Tool description**

```java
    @Tool(
        name = "searchKnowledgeBase",
        description = """
            在企业知识库文档中检索信息，包括 Markdown（.md）、PDF、Word（.docx）、TXT 文件。
            
            适用场景：
            - 查询 README、设计文档、使用手册、FAQ、流程规范、项目说明
            - 例如："怎么申请测试环境？"、"公司请假流程是什么？"、"项目架构是怎样的？"
            
            不适用场景（请调用其他工具）：
            - 代码检索 -> 使用 code_search
            - 数据库查询 -> 使用 database_query
            - API 文档 -> 使用 api_doc
            """
    )
```

- [ ] **Step 2: 修改 DatabaseQueryTool 的 @Tool description**

```java
    @Tool(
        name = "database_query",
        description = """
            查询企业业务数据库，仅支持 SELECT 查询，返回表格格式结果。
            自动限制最多返回 100 行，禁止 DDL/DML 操作。
            
            适用场景：
            - 查询用户、订单、产品等业务数据
            - 例如："查询最近 7 天注册的用户"、"本月订单总数是多少？"、"库存低于 10 的产品有哪些？"
            
            不适用场景：
            - 知识库文档查询 -> 使用 searchKnowledgeBase
            """
    )
```

- [ ] **Step 3: 修改 CodeSearchTool 的 @Tool description**

```java
    @Tool(
        name = "code_search",
        description = """
            在项目源码目录中搜索代码片段，支持按关键词和文件类型过滤。
            自动排除 target/ 和 .git/ 目录。
            
            适用场景：
            - 搜索函数定义、类定义、注释、配置
            - 例如："搜索 PaymentService 类"、"查找所有 @RestController 注解"、"搜索日志相关的配置"
            
            不适用场景：
            - 文档/README 查询 -> 使用 searchKnowledgeBase
            """
    )
```

- [ ] **Step 4: 修改 ApiDocTool 的 @Tool description**

```java
    @Tool(
        name = "api_doc",
        description = """
            扫描 Spring MVC 端点生成 API 文档，返回当前系统的 REST 接口信息。
            支持按关键字过滤端点。
            
            适用场景：
            - 查看系统有哪些 REST 接口、接口的请求方法和路径
            - 例如："生成 API 文档"、"查看用户相关的接口"、"有哪些 GET 接口？"
            
            不适用场景：
            - 接口的详细业务逻辑 -> 使用 code_search 搜索对应 Controller
            """
    )
```

- [ ] **Step 5: 编译验证**

```bash
cd /d/tmp/CompanyRag
mvn clean compile -pl company-rag-rag,company-rag-agent -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java
git add company-rag-agent/src/main/java/com/company/rag/agent/tool/DatabaseQueryTool.java
git add company-rag-agent/src/main/java/com/company/rag/agent/tool/CodeSearchTool.java
git add company-rag-agent/src/main/java/com/company/rag/agent/tool/ApiDocTool.java
git commit -m "enhance(agent): 完善 4 个工具的 @Tool description

- 每个工具 description 增加适用场景说明和 2-3 个调用示例
- 明确工具边界（什么场景该用、什么场景不该用）
- 帮助 LLM 更准确地选择工具"
```

---

## Task 2: 增强 ToolCallRecorder（traceId + 耗时 + 记录聚合）

**Files:**
- Modify: `company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java`
- Create: `company-rag-common/src/test/java/com/company/rag/common/tool/ToolCallRecorderTest.java`

- [ ] **Step 1: 重写 ToolCallRecorder**

```java
package com.company.rag.common.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具调用记录器（通用组件）
 * 支持 traceId 追踪、耗时记录、调用链路聚合
 */
@Slf4j
@Component
public class ToolCallRecorder {

    private static final int MAX_INPUT_LENGTH = 50;

    private final ThreadLocal<List<ToolCallRecord>> recordsHolder = new ThreadLocal<>();

    /**
     * 生成 traceId
     */
    public String generateTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 记录工具调用开始
     * @return 开始时间戳（毫秒）
     */
    public long recordStart(String traceId, String toolName, Map<String, Object> arguments) {
        String inputSummary = arguments != null
                ? arguments.toString().substring(0, Math.min(arguments.toString().length(), MAX_INPUT_LENGTH))
                : "";
        log.info("[TOOL_START] traceId={}, tool={}, input={}", traceId, toolName, inputSummary);
        return System.currentTimeMillis();
    }

    /**
     * 记录工具调用结束
     */
    public void recordEnd(String traceId, String toolName, long startTimeMs, String status) {
        recordEnd(traceId, toolName, startTimeMs, status, null);
    }

    /**
     * 记录工具调用结束（带错误信息）
     */
    public void recordEnd(String traceId, String toolName, long startTimeMs, String status, String errorMessage) {
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
    public List<ToolCallRecord> getAndClearRecords(String traceId) {
        List<ToolCallRecord> records = recordsHolder.get();
        recordsHolder.remove();
        if (records == null) {
            return List.of();
        }
        return records;
    }
}
```

- [ ] **Step 2: 创建 ToolCallRecord 模型类**

```java
package com.company.rag.common.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRecord {
    private String traceId;
    private String toolName;
    private long durationMs;
    private String status;       // success / failed
    private String errorMessage;
}
```

- [ ] **Step 3: 更新 KnowledgeBaseTool 使用新的 recordEnd 签名**

修改 `KnowledgeBaseTool.java` 中的 `recordEnd` 调用：

```java
// 原来：
recorder.recordEnd("searchKnowledgeBase", "success");
// 改为：
recorder.recordEnd(traceId, "searchKnowledgeBase", startTime, "success");
```

需要给 `searchKnowledgeBase` 方法增加 `traceId` 参数——但 `@Tool` 方法的参数都由 LLM 填充，我们不能加额外参数。

**解决方案**：通过 `ToolCallRecorder` 的 ThreadLocal 传递 traceId。

在 `RagAgentService.process()` 中生成 traceId 后，设置到 `ToolCallRecorder` 的 ThreadLocal 中，工具方法内从 `ToolCallRecorder` 获取。

- [ ] **Step 4: 在 ToolCallRecorder 中增加 traceId 存取**

```java
    /**
     * 设置当前线程的 traceId（在 RagAgentService 中调用）
     */
    public void setTraceId(String traceId) {
        traceIdHolder.set(traceId);
    }

    /**
     * 获取当前线程的 traceId（在工具方法中调用）
     */
    public String getTraceId() {
        return traceIdHolder.get();
    }

    /**
     * 清除当前线程的 traceId
     */
    public void clearTraceId() {
        traceIdHolder.remove();
    }

    private final ThreadLocal<String> traceIdHolder = new ThreadLocal<>();
```

然后 `KnowledgeBaseTool` 中：

```java
String traceId = recorder.getTraceId();
long startTime = recorder.recordStart(traceId, "searchKnowledgeBase", args);
// ... 执行逻辑 ...
recorder.recordEnd(traceId, "searchKnowledgeBase", startTime, "success");
```

- [ ] **Step 5: 编写 ToolCallRecorder 单元测试**

```java
package com.company.rag.common.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallRecorderTest {

    private ToolCallRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ToolCallRecorder();
    }

    @Test
    void shouldGenerateTraceId() {
        String traceId = recorder.generateTraceId();
        assertNotNull(traceId);
        assertEquals(8, traceId.length());
    }

    @Test
    void shouldRecordToolCall() {
        String traceId = "test123";
        recorder.setTraceId(traceId);
        
        long startTime = recorder.recordStart(traceId, "testTool", Map.of("key", "value"));
        
        recorder.recordEnd(traceId, "testTool", startTime, "success");
        
        List<ToolCallRecord> records = recorder.getAndClearRecords(traceId);
        assertEquals(1, records.size());
        assertEquals("testTool", records.get(0).getToolName());
        assertEquals("success", records.get(0).getStatus());
        assertTrue(records.get(0).getDurationMs() >= 0);
    }

    @Test
    void shouldRecordMultipleTools() {
        String traceId = "test456";
        recorder.setTraceId(traceId);
        
        long start1 = recorder.recordStart(traceId, "tool1", null);
        recorder.recordEnd(traceId, "tool1", start1, "success");
        
        long start2 = recorder.recordStart(traceId, "tool2", null);
        recorder.recordEnd(traceId, "tool2", start2, "failed", "error msg");
        
        List<ToolCallRecord> records = recorder.getAndClearRecords(traceId);
        assertEquals(2, records.size());
        assertEquals("tool1", records.get(0).getToolName());
        assertEquals("tool2", records.get(1).getToolName());
        assertEquals("error msg", records.get(1).getErrorMessage());
    }

    @Test
    void shouldReturnEmptyListWhenNoRecords() {
        List<ToolCallRecord> records = recorder.getAndClearRecords("notrace");
        assertTrue(records.isEmpty());
    }

    @Test
    void shouldClearRecordsAfterGet() {
        String traceId = "test789";
        recorder.setTraceId(traceId);
        
        long start = recorder.recordStart(traceId, "tool", null);
        recorder.recordEnd(traceId, "tool", start, "success");
        
        recorder.getAndClearRecords(traceId);
        
        // 再次获取应该为空
        List<ToolCallRecord> records = recorder.getAndClearRecords(traceId);
        assertTrue(records.isEmpty());
    }
}
```

- [ ] **Step 6: 运行测试**

```bash
cd /d/tmp/CompanyRag
mvn test -pl company-rag-common -Dtest=ToolCallRecorderTest -q
```

Expected: PASS (5 tests)

- [ ] **Step 7: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java
git add company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecord.java
git add company-rag-common/src/test/java/com/company/rag/common/tool/ToolCallRecorderTest.java
git commit -m "enhance(common): 增强 ToolCallRecorder 支持 traceId 和耗时记录

- 新增 ToolCallRecord 模型类
- ToolCallRecorder 支持 traceId 追踪、耗时计算
- ThreadLocal 存储调用记录，支持 getAndClearRecords 聚合
- 5 个单元测试覆盖正常/多工具/空记录/清除场景"
```

---

## Task 3: 修改 RagAgentService 集成可解释性日志

**Files:**
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java`

- [ ] **Step 1: 修改 RagAgentService.process() 增加日志聚合**

```java
package com.company.rag.agent.service;

import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.common.tool.ToolCallRecord;
import com.company.rag.common.tool.ToolCallRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG Agent 服务
 * 基于 Spring AI ChatClient 实现智能工具调用编排
 * 
 * Agent 模式工作流程：
 * 1. 用户提问 → ChatClient 分析意图
 * 2. ChatClient 自动决定是否需要调用工具（Function Calling）
 * 3. 如果需要：自动选择工具 → 执行 → 将结果反馈给 LLM
 * 4. LLM 基于工具结果生成最终回答
 * 5. 流式返回给用户
 */
@Slf4j
@Service
public class RagAgentService {

    private final ChatModel chatModel;
    private final ToolCallbackProvider toolCallbackProvider;
    private final AgentToolRegistry toolRegistry;
    private final ToolCallRecorder toolCallRecorder;
    
    private final ChatClient chatClient;

    /**
     * 构造方法，初始化 ChatClient 并注册工具
     */
    public RagAgentService(ChatModel chatModel, 
                           ToolCallbackProvider toolCallbackProvider,
                           AgentToolRegistry toolRegistry,
                           ToolCallRecorder toolCallRecorder) {
        this.chatModel = chatModel;
        this.toolCallbackProvider = toolCallbackProvider;
        this.toolRegistry = toolRegistry;
        this.toolCallRecorder = toolCallRecorder;
        
        // 构建 ChatClient，注册工具回调
        this.chatClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(toolCallbackProvider)
                .build();
        
        // 调试日志：输出工具信息
        log.info("RagAgentService 初始化：chatModel={}, toolCallbackProvider={}", 
                 chatModel.getClass().getSimpleName(), 
                 toolCallbackProvider != null ? toolCallbackProvider.getClass().getSimpleName() : "null");
        
        if (toolCallbackProvider != null) {
            var callbacks = toolCallbackProvider.getToolCallbacks();
            log.info("注册的工具数量：{}", callbacks.length);
            for (var callback : callbacks) {
                log.info("  - 工具：{}, 描述：{}", 
                         callback.getToolDefinition().name(),
                         callback.getToolDefinition().description());
            }
        }
    }

    /**
     * 处理 Agent 请求，自动选择工具
     * @param userMessage 用户消息
     * @return Agent 处理结果（包含回答和工具上下文）
     */
    public AgentResult process(String userMessage) {
        log.info("收到 Agent 请求：{}", userMessage);
        
        // 生成 traceId，设置到 ToolCallRecorder
        String traceId = toolCallRecorder.generateTraceId();
        toolCallRecorder.setTraceId(traceId);
        
        long startTime = System.currentTimeMillis();
        
        try {
            // ChatClient 自动处理工具调用
            String response = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();
            
            long totalDuration = System.currentTimeMillis() - startTime;
            
            // 获取工具调用记录
            List<ToolCallRecord> toolRecords = toolCallRecorder.getAndClearRecords(traceId);
            
            // 输出结构化可解释性日志
            String toolsSummary = toolRecords.stream()
                    .map(r -> String.format("%s(%dms,%s)", 
                            r.getToolName(), r.getDurationMs(), r.getStatus()))
                    .collect(Collectors.joining(", "));
            
            log.info("[AGENT] traceId={}, userMsg=\"{}\", tools=[{}], total={}ms",
                    traceId, userMessage, toolsSummary, totalDuration);
            
            log.info("Agent 处理完成，回答长度：{}", response != null ? response.length() : 0);
            return new AgentResult(response != null ? response : "", null);
            
        } catch (Exception e) {
            long totalDuration = System.currentTimeMillis() - startTime;
            log.error("[AGENT] traceId={}, userMsg=\"{}\", error={}, total={}ms",
                    traceId, userMessage, e.getMessage(), totalDuration);
            
            toolCallRecorder.clearTraceId();
            return new AgentResult("抱歉，系统繁忙，请稍后重试。", "error:" + e.getMessage());
        }
    }

    // queryDatabase, searchCode, getApiDoc 方法保持不变
    // ...
}
```

- [ ] **Step 2: 更新 KnowledgeBaseTool 使用新的 ToolCallRecorder API**

```java
package com.company.rag.rag.tools;

import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.rag.model.KnowledgeBaseResult;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.service.RagSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 工具：企业知识库智能问答（RAG）
 * 将自然语言问题转换为 RAG 检索，返回带引用来源的答案
 */
@Slf4j
@Component
public class KnowledgeBaseTool {
    
    private final RagSearchService ragSearchService;
    private final ToolCallRecorder recorder;
    
    public KnowledgeBaseTool(RagSearchService ragSearchService,
                            ToolCallRecorder recorder) {
        this.ragSearchService = ragSearchService;
        this.recorder = recorder;
    }
    
    @Tool(
        name = "searchKnowledgeBase",
        description = """
            在企业知识库文档中检索信息，包括 Markdown（.md）、PDF、Word（.docx）、TXT 文件。
            
            适用场景：
            - 查询 README、设计文档、使用手册、FAQ、流程规范、项目说明
            - 例如："怎么申请测试环境？"、"公司请假流程是什么？"、"项目架构是怎样的？"
            
            不适用场景（请调用其他工具）：
            - 代码检索 -> 使用 code_search
            - 数据库查询 -> 使用 database_query
            - API 文档 -> 使用 api_doc
            """
    )
    public KnowledgeBaseResult searchKnowledgeBase(
            @ToolParam(description = "用户自然语言问题，例如：怎么申请测试环境？") String question,
            @ToolParam(description = "返回文档片段数量上限，默认 5", required = false) Integer topK) {
        
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("question", question);
        if (topK != null) {
            args.put("topK", topK);
        }
        
        // 从 ToolCallRecorder 获取 traceId
        String traceId = recorder.getTraceId();
        long startTime = recorder.recordStart(traceId, "searchKnowledgeBase", args);
        
        try {
            // 参数校验
            if (question == null || question.trim().isEmpty()) {
                recorder.recordEnd(traceId, "searchKnowledgeBase", startTime, "failed");
                return KnowledgeBaseResult.failed("问题不能为空");
            }
            
            // 调用 RAG 引擎（混合检索 + Rerank）
            int effectiveTopK = (topK == null || topK <= 0) ? 5 : topK;
            RagQuery query = new RagQuery();
            query.setQuery(question);
            query.setTopK(effectiveTopK);
            RagResult result = ragSearchService.search(query);
            
            // 转换为 KnowledgeBaseResult
            KnowledgeBaseResult response = convertToKnowledgeBaseResult(result);
            
            if (response.isSuccess()) {
                recorder.recordEnd(traceId, "searchKnowledgeBase", startTime, "success");
            } else {
                recorder.recordEnd(traceId, "searchKnowledgeBase", startTime, "failed");
            }
            
            return response;
            
        } catch (Exception e) {
            log.error("知识库工具调用失败：question={}, err={}", question, e.getMessage());
            recorder.recordEnd(traceId, "searchKnowledgeBase", startTime, "failed", e.getMessage());
            return KnowledgeBaseResult.failed("工具调用失败：" + e.getMessage());
        }
    }
    
    // convertToKnowledgeBaseResult 和 buildAnswerFromChunks 方法保持不变
    // ...
}
```

- [ ] **Step 3: 编译验证**

```bash
cd /d/tmp/CompanyRag
mvn clean compile -pl company-rag-common,company-rag-rag,company-rag-agent -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java
git add company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java
git commit -m "enhance(agent): 增加可解释性日志

- RagAgentService 生成 traceId，聚合工具调用记录
- 输出结构化日志：[AGENT] traceId=xxx, tools=[toolA(100ms,success)], total=500ms
- KnowledgeBaseTool 使用新的 ToolCallRecorder API
- 异常时清除 ThreadLocal 防止内存泄漏"
```

---

## Task 4: 添加 Caffeine 缓存

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/config/RagCacheConfig.java`
- Modify: `company-rag-rag/pom.xml`

- [ ] **Step 1: 创建 RagCacheConfig**

```java
package com.company.rag.rag.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * RAG 缓存配置
 * 使用 Caffeine 内存缓存，减少重复 RAG 查询的延迟
 * 
 * 缓存策略：
 * - TTL: 5 分钟（相同问题短时间内重复提问命中缓存）
 * - 最大条目: 100 条（防止内存溢出）
 * - 记录统计信息（命中率等）
 */
@Configuration
@EnableCaching
public class RagCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("ragResults");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(100)
                .recordStats());
        return cacheManager;
    }
}
```

- [ ] **Step 2: 在 KnowledgeBaseTool 的 searchKnowledgeBase 方法上添加 @Cacheable 注解**

在 `KnowledgeBaseTool.java` 的 `searchKnowledgeBase` 方法上添加：

```java
    @Tool(
        name = "searchKnowledgeBase",
        description = """
            在企业知识库文档中检索信息，包括 Markdown（.md）、PDF、Word（.docx）、TXT 文件。
            ...
            """
    )
    @Cacheable(value = "ragResults", key = "#question + ':' + #topK", unless = "#result != null and !#result.success")
    public KnowledgeBaseResult searchKnowledgeBase(
            @ToolParam(description = "用户自然语言问题，例如：怎么申请测试环境？") String question,
            @ToolParam(description = "返回文档片段数量上限，默认 5", required = false) Integer topK) {
```

并在 import 中添加：

```java
import org.springframework.cache.annotation.Cacheable;
```

- [ ] **Step 3: 修改 company-rag-rag/pom.xml 添加缓存依赖**

在 `<dependencies>` 中添加：

```xml
        <!-- Spring Cache + Caffeine -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-cache</artifactId>
        </dependency>
```

- [ ] **Step 4: 编译验证**

```bash
cd /d/tmp/CompanyRag
mvn clean compile -pl company-rag-rag -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/config/RagCacheConfig.java
git add company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java
git add company-rag-rag/pom.xml
git commit -m "feat(rag): 添加 Caffeine 缓存支持

- 新增 RagCacheConfig 缓存配置
- TTL 5 分钟，最大 100 条，记录命中率统计
- KnowledgeBaseTool 的 searchKnowledgeBase 方法使用 @Cacheable
- 相同问题 5 分钟内第二次请求命中缓存"
```

---

## Task 5: 完整编译验证

- [ ] **Step 1: 全量编译**

```bash
cd /d/tmp/CompanyRag
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 运行所有单元测试**

```bash
cd /d/tmp/CompanyRag
mvn test -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交最终验证结果**

```bash
cd /d/tmp/CompanyRag
git add -A
git commit -m "chore: 第二阶段增强完成编译验证"
```

---

## 验收检查清单

- [ ] 4 个工具的 `@Tool` description 包含场景说明和 2-3 个调用示例
- [ ] Agent 每次请求输出 `[AGENT] traceId=xxx, tools=[...], total=...ms` 日志
- [ ] 相同 RAG 问题 5 分钟内第二次请求命中 Caffeine 缓存
- [ ] ToolCallRecorderTest 5 个测试全部通过
- [ ] `mvn clean compile` 无错误
- [ ] `mvn test` 无失败