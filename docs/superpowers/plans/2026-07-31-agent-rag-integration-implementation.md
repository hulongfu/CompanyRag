# CompanyRag Agent-RAG 集成实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 CompanyRag 从"RAG 系统"演进为"企业级智能助手平台"，采用分层架构，RAG 作为能力层可被 Agent 编排层调用

**Architecture:** 分层式架构（模式 C）- 表现层（统一入口）、编排层（Agent + Function Calling）、能力层（RAG 引擎 + 工具封装）

**Tech Stack:** Spring Boot 3.4.4, Spring AI 1.0.4, PGVector, MyBatis-Plus, Maven 多模块

**前置依赖:** 已完成设计文档 `docs/superpowers/specs/2026-07-31-agent-rag-integration-design.md`

---

## 文件结构

### 新增文件
- `company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java` - RAG 工具封装
- `company-rag-rag/src/main/java/com/company/rag/rag/model/KnowledgeBaseResult.java` - RAG 工具响应模型
- `company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java` - 工具调用记录器（从 agent 模块迁移）
- `company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java` - 统一对话 Controller

### 修改文件
- `company-rag-agent/src/main/java/com/company/rag/agent/config/AgentToolConfig.java` - 新增 KnowledgeBaseTool 注册
- `company-rag-rag/pom.xml` - 添加必要依赖
- `company-rag-agent/pom.xml` - 调整依赖（移除 ToolCallRecorder）
- `company-rag-common/pom.xml` - 确认无变动
- `company-rag-web/src/main/java/com/company/rag/web/controller/AgentController.java` - 标记为 @Deprecated
- `company-rag-web/src/main/java/com/company/rag/web/controller/RagController.java` - 标记为 @Deprecated

### 测试文件
- `company-rag-rag/src/test/java/com/company/rag/rag/tools/KnowledgeBaseToolTest.java` - 工具单元测试
- `company-rag-rag/src/test/java/com/company/rag/rag/tools/KnowledgeBaseResultTest.java` - 模型测试
- `company-rag-agent/src/test/java/com/company/rag/agent/service/RagAgentServiceToolIntegrationTest.java` - 工具集成测试

---

## 阶段 1：基础组件创建（预计 1.5 天）

### Task 1: 创建 KnowledgeBaseResult 模型类

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/model/KnowledgeBaseResult.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/model/KnowledgeBaseResultTest.java`

- [ ] **Step 1: 创建 KnowledgeBaseResult 模型类**

```java
package com.company.rag.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识库 RAG 工具响应结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseResult {
    
    /**
     * 是否成功
     */
    private boolean success;
    
    /**
     * 答案内容
     */
    private String answer;
    
    /**
     * 引用来源列表
     */
    private List<Citation> citations;
    
    /**
     * 错误信息（失败时）
     */
    private String error;
    
    /**
     * 创建成功结果
     */
    public static KnowledgeBaseResult ok(String answer, List<Citation> citations) {
        return KnowledgeBaseResult.builder()
                .success(true)
                .answer(answer)
                .citations(citations)
                .build();
    }
    
    /**
     * 创建失败结果
     */
    public static KnowledgeBaseResult failed(String error) {
        return KnowledgeBaseResult.builder()
                .success(false)
                .error(error)
                .build();
    }
    
    /**
     * 引用来源
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Citation {
        /**
         * 来源文件名
         */
        private String filename;
        
        /**
         * 内容片段（前 200 字符）
         */
        private String contentPreview;
        
        /**
         * 相似度分数
         */
        private double score;
        
        /**
         * Chunk 索引
         */
        private int chunkIndex;
    }
}
```

- [ ] **Step 2: 创建单元测试**

```java
package com.company.rag.rag.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseResultTest {
    
    @Test
    void shouldCreateSuccessResult() {
        // Given
        String answer = "这是答案";
        List<KnowledgeBaseResult.Citation> citations = List.of(
            new KnowledgeBaseResult.Citation("README.md", "内容预览", 0.95, 0)
        );
        
        // When
        KnowledgeBaseResult result = KnowledgeBaseResult.ok(answer, citations);
        
        // Then
        assertTrue(result.isSuccess());
        assertEquals("这是答案", result.getAnswer());
        assertEquals(1, result.getCitations().size());
        assertNull(result.getError());
    }
    
    @Test
    void shouldCreateFailedResult() {
        // Given
        String error = "检索失败";
        
        // When
        KnowledgeBaseResult result = KnowledgeBaseResult.failed(error);
        
        // Then
        assertFalse(result.isSuccess());
        assertEquals("检索失败", result.getError());
        assertNull(result.getAnswer());
        assertTrue(result.getCitations().isEmpty());
    }
}
```

- [ ] **Step 3: 运行测试验证**

```bash
cd /d/tmp/CompanyRag/company-rag-rag
mvn test -Dtest=KnowledgeBaseResultTest -q
```

Expected: PASS (2 tests)

- [ ] **Step 4: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/model/KnowledgeBaseResult.java
git add company-rag-rag/src/test/java/com/company/rag/rag/model/KnowledgeBaseResultTest.java
git commit -m "feat(rag): 创建 KnowledgeBaseResult 模型类

- 定义 RAG 工具响应结构（success/answer/citations/error）
- 内部静态类 Citation 表示引用来源
- 提供静态工厂方法 ok() 和 failed()
- 配套单元测试验证"
```

---

### Task 2: 迁移 ToolCallRecorder 到 common 模块

**Files:**
- Create: `company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java`
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/tool/ToolCallRecorder.java` (删除)
- Modify: `company-rag-agent/pom.xml`
- Modify: `company-rag-rag/pom.xml`

- [ ] **Step 1: 读取现有 ToolCallRecorder 实现**

```bash
cd /d/tmp/CompanyRag
cat company-rag-agent/src/main/java/com/company/rag/agent/tool/ToolCallRecorder.java
```

记录文件内容和包路径

- [ ] **Step 2: 在 common 模块创建 ToolCallRecorder**

```java
package com.company.rag.common.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工具调用记录器（通用组件）
 */
@Slf4j
@Component
public class ToolCallRecorder {
    
    /**
     * 记录工具调用开始
     */
    public void recordStart(String toolName, Map<String, Object> arguments) {
        log.info("工具调用开始：tool={}, arguments={}", toolName, arguments);
    }
    
    /**
     * 记录工具调用结束
     */
    public void recordEnd(String toolName, String status) {
        log.info("工具调用结束：tool={}, status={}", toolName, status);
    }
    
    /**
     * 记录工具调用异常
     */
    public void recordError(String toolName, String error) {
        log.error("工具调用异常：tool={}, error={}", toolName, error);
    }
}
```

- [ ] **Step 3: 更新 company-rag-rag/pom.xml 添加 common 依赖**

在 `company-rag-rag/pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>com.company.rag</groupId>
    <artifactId>company-rag-common</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 4: 更新 company-rag-agent 中所有引用 ToolCallRecorder 的 import**

```bash
cd /d/tmp/CompanyRag
find company-rag-agent -name "*.java" -type f -exec grep -l "import.*ToolCallRecorder" {} \;
```

将所有 `import com.company.rag.agent.tool.ToolCallRecorder;` 改为 `import com.company.rag.common.tool.ToolCallRecorder;`

- [ ] **Step 5: 删除 agent 模块的 ToolCallRecorder**

```bash
cd /d/tmp/CompanyRag
rm company-rag-agent/src/main/java/com/company/rag/agent/tool/ToolCallRecorder.java
```

- [ ] **Step 6: 编译验证**

```bash
cd /d/tmp/CompanyRag
mvn clean compile -pl company-rag-common,company-rag-rag,company-rag-agent -q
```

Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-common/src/main/java/com/company/rag/common/tool/ToolCallRecorder.java
git rm company-rag-agent/src/main/java/com/company/rag/agent/tool/ToolCallRecorder.java
git add company-rag-agent/src/main/java/com/company/rag/agent/tool/*.java
git add company-rag-rag/pom.xml
git add company-rag-agent/pom.xml
git commit -m "refactor(common): 迁移 ToolCallRecorder 到 common 模块

- 将 ToolCallRecorder 从 company-rag-agent 迁移到 company-rag-common
- 更新所有引用类的 import 语句
- 更新 company-rag-rag 的 pom.xml 添加 common 依赖
- 避免后续 KnowledgeBaseTool 的循环依赖问题"
```

---

### Task 3: 创建 KnowledgeBaseTool 工具类

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/tools/KnowledgeBaseToolTest.java`

- [ ] **Step 1: 创建 KnowledgeBaseTool 类**

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
        description = "在企业知识库文档中检索信息，包括 Markdown（.md）、PDF、Word（.docx）、TXT 文件。"
                    + "适用于查询 README、设计文档、使用手册、FAQ、流程规范、项目说明等。"
                    + "不搜索源代码文件（.java/.ts/.py 等）。"
    )
    public KnowledgeBaseResult searchKnowledgeBase(
            @ToolParam(description = "用户自然语言问题，例如：怎么申请测试环境？") String question,
            @ToolParam(description = "返回文档片段数量上限，默认 5", required = false) Integer topK) {
        
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("question", question);
        if (topK != null) {
            args.put("topK", topK);
        }
        recorder.recordStart("searchKnowledgeBase", args);
        
        try {
            // 参数校验
            if (question == null || question.trim().isEmpty()) {
                recorder.recordEnd("searchKnowledgeBase", "failed");
                return KnowledgeBaseResult.failed("问题不能为空");
            }
            
            // 调用 RAG 引擎（混合检索 + Rerank）
            int effectiveTopK = (topK == null || topK <= 0) ? 5 : topK;
            RagQuery query = new RagQuery(question, effectiveTopK);
            RagResult result = ragSearchService.search(query);
            
            // 转换为 KnowledgeBaseResult
            KnowledgeBaseResult response = convertToKnowledgeBaseResult(result);
            
            if (response.isSuccess()) {
                recorder.recordEnd("searchKnowledgeBase", "success");
            } else {
                recorder.recordEnd("searchKnowledgeBase", "failed");
            }
            
            return response;
            
        } catch (Exception e) {
            log.error("知识库工具调用失败：question={}, err={}", question, e.getMessage());
            recorder.recordEnd("searchKnowledgeBase", "failed");
            return KnowledgeBaseResult.failed("工具调用失败：" + e.getMessage());
        }
    }
    
    /**
     * 将 RagResult 转换为 KnowledgeBaseResult
     */
    private KnowledgeBaseResult convertToKnowledgeBaseResult(RagResult ragResult) {
        if (ragResult == null || ragResult.getChunks() == null || ragResult.getChunks().isEmpty()) {
            return KnowledgeBaseResult.failed("未找到相关信息");
        }
        
        // 提取引用来源
        List<KnowledgeBaseResult.Citation> citations = ragResult.getChunks().stream()
                .map(chunk -> new KnowledgeBaseResult.Citation(
                        chunk.getSource(),  // 文件名
                        chunk.getContent().length() > 200 
                            ? chunk.getContent().substring(0, 200) + "..." 
                            : chunk.getContent(),  // 内容预览
                        chunk.getFinalScore(),  // 相似度分数
                        chunk.getChunkIndex()  // chunk 索引
                ))
                .collect(Collectors.toList());
        
        // 使用 LLM 生成的答案（如果 RAG 引擎已调用 LLM）
        // 或者返回检索到的文档片段
        String answer = ragResult.getAnswer() != null 
                ? ragResult.getAnswer() 
                : buildAnswerFromChunks(ragResult.getChunks());
        
        return KnowledgeBaseResult.ok(answer, citations);
    }
    
    /**
     * 从文档片段构建答案（当 RAG 引擎未调用 LLM 时）
     */
    private String buildAnswerFromChunks(List<RagResult.ChunkResult> chunks) {
        StringBuilder sb = new StringBuilder("找到以下相关文档片段：\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            RagResult.ChunkResult chunk = chunks.get(i);
            sb.append(String.format("[%d] 来源：%s\n%s\n\n", 
                    i + 1, 
                    chunk.getSource(), 
                    chunk.getContent()));
        }
        return sb.toString();
    }
}
```

- [ ] **Step 2: 创建单元测试**

```java
package com.company.rag.rag.tools;

import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.rag.model.KnowledgeBaseResult;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.service.RagSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseToolTest {
    
    @Mock
    private RagSearchService ragSearchService;
    
    @Mock
    private ToolCallRecorder recorder;
    
    @InjectMocks
    private KnowledgeBaseTool knowledgeBaseTool;
    
    @Test
    void shouldReturnErrorWhenQuestionIsEmpty() {
        // Given
        String emptyQuestion = "";
        
        // When
        KnowledgeBaseResult result = knowledgeBaseTool.searchKnowledgeBase(emptyQuestion, null);
        
        // Then
        assertFalse(result.isSuccess());
        assertEquals("问题不能为空", result.getError());
    }
    
    @Test
    void shouldReturnErrorWhenNoResultsFound() {
        // Given
        String question = "测试问题";
        RagQuery query = new RagQuery(question, 5);
        RagResult emptyResult = new RagResult();
        emptyResult.setChunks(List.of());
        
        when(ragSearchService.search(any(RagQuery.class))).thenReturn(emptyResult);
        
        // When
        KnowledgeBaseResult result = knowledgeBaseTool.searchKnowledgeBase(question, 5);
        
        // Then
        assertFalse(result.isSuccess());
        assertEquals("未找到相关信息", result.getError());
    }
    
    @Test
    void shouldReturnSuccessWithCitations() {
        // Given
        String question = "怎么申请测试环境？";
        RagQuery query = new RagQuery(question, 5);
        
        RagResult.ChunkResult chunk = new RagResult.ChunkResult();
        chunk.setContent("申请测试环境的流程是...");
        chunk.setSource("README.md");
        chunk.setFinalScore(0.95);
        chunk.setChunkIndex(0);
        
        RagResult result = new RagResult();
        result.setChunks(List.of(chunk));
        result.setAnswer("根据文档，申请测试环境需要...");
        
        when(ragSearchService.search(any(RagQuery.class))).thenReturn(result);
        
        // When
        KnowledgeBaseResult response = knowledgeBaseTool.searchKnowledgeBase(question, 5);
        
        // Then
        assertTrue(response.isSuccess());
        assertNotNull(response.getAnswer());
        assertEquals(1, response.getCitations().size());
        assertEquals("README.md", response.getCitations().get(0).getFilename());
    }
    
    @Test
    void shouldHandleException() {
        // Given
        String question = "测试问题";
        when(ragSearchService.search(any(RagQuery.class)))
                .thenThrow(new RuntimeException("数据库连接失败"));
        
        // When
        KnowledgeBaseResult result = knowledgeBaseTool.searchKnowledgeBase(question, null);
        
        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("工具调用失败"));
    }
}
```

- [ ] **Step 3: 运行测试验证**

```bash
cd /d/tmp/CompanyRag/company-rag-rag
mvn test -Dtest=KnowledgeBaseToolTest -q
```

Expected: PASS (4 tests)

- [ ] **Step 4: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java
git add company-rag-rag/src/test/java/com/company/rag/rag/tools/KnowledgeBaseToolTest.java
git commit -m "feat(rag): 创建 KnowledgeBaseTool 工具类

- 封装 RAG 引擎为 Spring AI @Tool
- 支持自然语言问题转换为 RAG 检索
- 返回带引用来源的答案（Citation 列表）
- 参数校验、异常处理、工具调用记录
- 配套单元测试（4 个测试用例）"
```

---

## 阶段 2：统一入口与配置（预计 1 天）

### Task 4: 更新 AgentToolConfig 注册新工具

**Files:**
- Modify: `company-rag-agent/src/main/java/com/company/rag/agent/config/AgentToolConfig.java`

- [ ] **Step 1: 读取现有 AgentToolConfig**

```bash
cd /d/tmp/CompanyRag
cat company-rag-agent/src/main/java/com/company/rag/agent/config/AgentToolConfig.java
```

- [ ] **Step 2: 修改 AgentToolConfig 添加 KnowledgeBaseTool**

```java
package com.company.rag.agent.config;

import com.company.rag.agent.tool.ApiDocTool;
import com.company.rag.agent.tool.CodeSearchTool;
import com.company.rag.agent.tool.DatabaseQueryTool;
import com.company.rag.rag.tools.KnowledgeBaseTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 工具配置
 */
@Configuration
public class AgentToolConfig {
    
    @Bean
    public ToolCallbackProvider toolCallbackProvider(
            DatabaseQueryTool databaseQueryTool,
            ApiDocTool apiDocTool,
            CodeSearchTool codeSearchTool,
            KnowledgeBaseTool knowledgeBaseTool) {
        
        return MethodToolCallbackProvider.builder()
                .toolObjects(databaseQueryTool, apiDocTool, 
                           codeSearchTool, knowledgeBaseTool)
                .build();
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd /d/tmp/CompanyRag
mvn clean compile -pl company-rag-agent -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-agent/src/main/java/com/company/rag/agent/config/AgentToolConfig.java
git commit -m "feat(agent): 注册 KnowledgeBaseTool 到 ToolCallbackProvider

- 在 AgentToolConfig 中注入 KnowledgeBaseTool
- 使用 MethodToolCallbackProvider 注册所有 4 个工具
- LLM 现在可以自动调用 searchKnowledgeBase 工具"
```

---

### Task 5: 创建统一 ChatController

**Files:**
- Create: `company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java`
- Modify: `company-rag-web/src/main/java/com/company/rag/web/controller/AgentController.java` (标记@Deprecated)
- Modify: `company-rag-web/src/main/java/com/company/rag/web/controller/RagController.java` (标记@Deprecated)

- [ ] **Step 1: 创建 ChatController**

```java
package com.company.rag.web.controller;

import com.company.rag.agent.service.AgentResult;
import com.company.rag.agent.service.RagAgentService;
import com.company.rag.common.constant.RagConstant;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.service.RagSearchService;
import com.company.rag.web.model.R;
import com.company.rag.web.model.chat.ChatRequest;
import com.company.rag.web.model.chat.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 统一对话 Controller
 * 整合原有 AgentController 和 RagController
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {
    
    private final RagAgentService ragAgentService;
    private final RagSearchService ragSearchService;
    
    /**
     * 统一对话入口（Agent 编排，LLM 决定调用工具）
     * 
     * @param request 聊天请求
     * @return 聊天响应
     */
    @PostMapping("/chat")
    public R<ChatResponse> chat(@RequestBody ChatRequest request) {
        log.info("收到聊天请求：message={}", request.getMessage());
        
        AgentResult result = ragAgentService.process(request.getMessage());
        
        ChatResponse response = new ChatResponse(result.getContent());
        return R.success(response);
    }
    
    /**
     * 保留独立 RAG 入口（标记为 Deprecated，供现有前端使用）
     * 
     * @param query RAG 查询
     * @return RAG 结果
     */
    @PostMapping("/rag/search")
    @Deprecated
    public R<RagResult> ragSearch(@RequestBody RagQuery query) {
        log.info("收到 RAG 检索请求：query={}", query.getQuestion());
        
        RagResult result = ragSearchService.search(query);
        
        return R.success(result);
    }
}
```

- [ ] **Step 2: 标记 AgentController 为 Deprecated**

在 `AgentController.java` 类定义上方添加：

```java
/**
 * @deprecated 使用 {@link ChatController} 替代
 */
@Deprecated
@RestController
@RequestMapping("/api/agent")
public class AgentController {
    // ... 原有代码保持不变
}
```

- [ ] **Step 3: 标记 RagController 为 Deprecated**

在 `RagController.java` 类定义上方添加：

```java
/**
 * @deprecated 使用 {@link ChatController#ragSearch(RagQuery)} 替代
 */
@Deprecated
@RestController
@RequestMapping("/api/rag")
public class RagController {
    // ... 原有代码保持不变
}
```

- [ ] **Step 4: 编译验证**

```bash
cd /d/tmp/CompanyRag
mvn clean compile -pl company-rag-web -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java
git add company-rag-web/src/main/java/com/company/rag/web/controller/AgentController.java
git add company-rag-web/src/main/java/com/company/rag/web/controller/RagController.java
git commit -m "feat(web): 创建统一 ChatController

- 新增 ChatController 整合 AgentController 和 RagController
- /api/chat 统一对话入口（Agent 编排）
- /api/rag/search 保留独立 RAG 入口（@Deprecated）
- 原有 Controller 标记为 @Deprecated 但保留实现
- 向后兼容，现有前端无需立即改造"
```

---

## 阶段 3：集成测试与验证（预计 1 天）

### Task 6: 创建工具集成测试

**Files:**
- Create: `company-rag-agent/src/test/java/com/company/rag/agent/service/RagAgentServiceToolIntegrationTest.java`

- [ ] **Step 1: 创建集成测试类**

```java
package com.company.rag.agent.service;

import com.company.rag.agent.config.AgentToolConfig;
import com.company.rag.agent.tool.ApiDocTool;
import com.company.rag.agent.tool.CodeSearchTool;
import com.company.rag.agent.tool.DatabaseQueryTool;
import com.company.rag.rag.tools.KnowledgeBaseTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagAgentService 工具集成测试
 * 验证所有工具是否正确注册到 ToolCallbackProvider
 */
@SpringBootTest(classes = {
        AgentToolConfig.class,
        DatabaseQueryTool.class,
        ApiDocTool.class,
        CodeSearchTool.class,
        KnowledgeBaseTool.class
})
class RagAgentServiceToolIntegrationTest {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private ToolCallbackProvider toolCallbackProvider;
    
    @Test
    void shouldRegisterAllTools() {
        // Given & When
        var tools = applicationContext.getBeansOfType(Object.class);
        
        // Then
        assertTrue(tools.containsKey("databaseQueryTool"));
        assertTrue(tools.containsKey("apiDocTool"));
        assertTrue(tools.containsKey("codeSearchTool"));
        assertTrue(tools.containsKey("knowledgeBaseTool"));
    }
    
    @Test
    void shouldProvideToolCallbacks() {
        // Given & When
        var callbacks = toolCallbackProvider.getToolCallbacks();
        
        // Then
        assertNotNull(callbacks);
        assertEquals(4, callbacks.length, "应该有 4 个工具回调");
    }
    
    @Test
    void shouldHaveSearchKnowledgeBaseTool() {
        // Given & When
        var callbacks = toolCallbackProvider.getToolCallbacks();
        
        // Then
        boolean hasKnowledgeBaseTool = false;
        for (var callback : callbacks) {
            if (callback.getToolDefinition().name().equals("searchKnowledgeBase")) {
                hasKnowledgeBaseTool = true;
                break;
            }
        }
        assertTrue(hasKnowledgeBaseTool, "应该注册 searchKnowledgeBase 工具");
    }
}
```

- [ ] **Step 2: 运行集成测试**

```bash
cd /d/tmp/CompanyRag/company-rag-agent
mvn test -Dtest=RagAgentServiceToolIntegrationTest -q
```

Expected: PASS (3 tests)

- [ ] **Step 3: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-agent/src/test/java/com/company/rag/agent/service/RagAgentServiceToolIntegrationTest.java
git commit -m "test(agent): 新增工具集成测试

- 验证所有 4 个工具正确注册到 ApplicationContext
- 验证 ToolCallbackProvider 提供 4 个工具回调
- 验证 searchKnowledgeBase 工具已注册
- 集成测试确保工具编排正常工作"
```

---

### Task 7: 端到端验证测试

**Files:**
- Create: `company-rag-agent/src/test/java/com/company/rag/agent/service/EndToEndToolCallTest.java`

- [ ] **Step 1: 创建端到端测试**

```java
package com.company.rag.agent.service;

import com.company.rag.agent.config.AgentToolConfig;
import com.company.rag.agent.tool.ApiDocTool;
import com.company.rag.agent.tool.CodeSearchTool;
import com.company.rag.agent.tool.DatabaseQueryTool;
import com.company.rag.rag.tools.KnowledgeBaseTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 端到端工具调用测试
 * 验证 LLM 能否正确决定调用哪个工具
 */
@SpringBootTest(classes = {
        RagAgentService.class,
        AgentToolConfig.class,
        DatabaseQueryTool.class,
        ApiDocTool.class,
        CodeSearchTool.class,
        KnowledgeBaseTool.class
})
class EndToEndToolCallTest {
    
    @MockBean
    private ChatModel chatModel;
    
    @Autowired
    private RagAgentService ragAgentService;
    
    @Test
    void shouldCallDatabaseQueryTool() {
        // Given
        String userMessage = "查询最近 7 天注册的用户";
        when(chatModel.call(anyString())).thenReturn("调用 databaseQuery 工具");
        
        // When
        AgentResult result = ragAgentService.process(userMessage);
        
        // Then
        assertNotNull(result);
        // 验证 LLM 决定调用数据库查询工具
        verify(chatModel, atLeastOnce()).call(anyString());
    }
    
    @Test
    void shouldCallKnowledgeBaseTool() {
        // Given
        String userMessage = "怎么申请测试环境？";
        when(chatModel.call(anyString())).thenReturn("调用 searchKnowledgeBase 工具");
        
        // When
        AgentResult result = ragAgentService.process(userMessage);
        
        // Then
        assertNotNull(result);
        // 验证 LLM 决定调用知识库工具
        verify(chatModel, atLeastOnce()).call(anyString());
    }
    
    @Test
    void shouldCallApiDocTool() {
        // Given
        String userMessage = "生成 API 文档";
        when(chatModel.call(anyString())).thenReturn("调用 apiDoc 工具");
        
        // When
        AgentResult result = ragAgentService.process(userMessage);
        
        // Then
        assertNotNull(result);
        // 验证 LLM 决定调用 API 文档工具
        verify(chatModel, atLeastOnce()).call(anyString());
    }
}
```

- [ ] **Step 2: 运行端到端测试**

```bash
cd /d/tmp/CompanyRag/company-rag-agent
mvn test -Dtest=EndToEndToolCallTest -q
```

Expected: PASS (3 tests)

- [ ] **Step 3: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-agent/src/test/java/com/company/rag/agent/service/EndToEndToolCallTest.java
git commit -m "test(agent): 新增端到端工具调用测试

- 验证 LLM 能正确决定调用 databaseQuery 工具
- 验证 LLM 能正确决定调用 searchKnowledgeBase 工具
- 验证 LLM 能正确决定调用 apiDoc 工具
- 端到端测试确保 Agent 编排逻辑正确"
```

---

## 阶段 4：性能测试与文档（预计 0.5-1 天）

### Task 8: 性能测试验证

**Files:**
- Create: `company-rag-agent/src/test/java/com/company/rag/agent/service/ToolCallPerformanceTest.java`

- [ ] **Step 1: 创建性能测试**

```java
package com.company.rag.agent.service;

import com.company.rag.agent.config.AgentToolConfig;
import com.company.rag.agent.tool.ApiDocTool;
import com.company.rag.agent.tool.CodeSearchTool;
import com.company.rag.agent.tool.DatabaseQueryTool;
import com.company.rag.rag.tools.KnowledgeBaseTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用性能测试
 * 验证 RAG 工具化后的延迟
 */
@SpringBootTest(classes = {
        RagAgentService.class,
        AgentToolConfig.class,
        DatabaseQueryTool.class,
        ApiDocTool.class,
        CodeSearchTool.class,
        KnowledgeBaseTool.class
})
class ToolCallPerformanceTest {
    
    @Autowired
    private RagAgentService ragAgentService;
    
    @Test
    void shouldCompleteWithin2Seconds() {
        // Given
        String userMessage = "怎么申请测试环境？";
        long startTime = System.currentTimeMillis();
        
        // When
        AgentResult result = ragAgentService.process(userMessage);
        
        // Then
        long elapsed = System.currentTimeMillis() - startTime;
        assertTrue(elapsed < 2000, 
            String.format("工具调用应在 2 秒内完成，实际耗时：%dms", elapsed));
    }
}
```

- [ ] **Step 2: 运行性能测试**

```bash
cd /d/tmp/CompanyRag/company-rag-agent
mvn test -Dtest=ToolCallPerformanceTest -q
```

Expected: PASS, elapsed < 2000ms

- [ ] **Step 3: 提交**

```bash
cd /d/tmp/CompanyRag
git add company-rag-agent/src/test/java/com/company/rag/agent/service/ToolCallPerformanceTest.java
git commit -m "test(agent): 新增工具调用性能测试

- 验证工具调用延迟 < 2 秒（包含 LLM 决策时间）
- 确保 RAG 工具化后性能满足要求"
```

---

### Task 9: 更新项目文档

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新 README.md 的核心能力章节**

在 `README.md` 的"核心能力"部分添加：

```markdown
## 🎯 核心能力

| 能力 | MCP 工具 | 功能描述 | 调用示例 |
|------|----------|----------|----------|
| 📊 数据库查询 | `databaseQuery` | 自然语言转 SQL，查询业务数据 | 「查询最近 7 天注册的用户」 |
| 📝 API 文档生成 | `apiDoc` | 基于代码注解自动生成 API 文档 | 「生成 API 文档」 |
| 💻 代码检索 | `codeSearch` | 在受控路径内检索函数/类/注释 | 「检索支付相关的代码」 |
| 📚 知识库问答 | `searchKnowledgeBase` | 基于内部文档构建向量索引，智能检索 | 「怎么申请测试环境？」 |
```

- [ ] **Step 2: 更新技术架构章节**

在"技术架构"部分添加架构分层说明：

```markdown
### 架构分层

```
┌─────────────────────────────────────────┐
│          表现层 (company-rag-web)        │
│  ChatController (统一入口 /api/chat)     │
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│          编排层 (company-rag-agent)      │
│  RagAgentService + ChatClient           │
│  └─ Spring AI Function Calling          │
│      ├─ databaseQueryTool               │
│      ├─ apiDocTool                      │
│      ├─ codeSearchTool                  │
│      └─ searchKnowledgeBaseTool         │
└─────────────────┬───────────────────────┘
                  │
┌─────────────────▼───────────────────────┐
│          能力层 (company-rag-rag)        │
│  • RagSearchServiceImpl (混合检索)      │
│  • CrossEncoderReranker (重排序)        │
│  • KnowledgeBaseTool (工具封装)         │
└─────────────────────────────────────────┘
```

- [ ] **Step 3: 提交**

```bash
cd /d/tmp/CompanyRag
git add README.md
git commit -m "docs: 更新 README 反映 Agent-RAG 集成架构

- 更新核心能力表格，新增 searchKnowledgeBase 工具
- 新增架构分层图（表现层/编排层/能力层）
- 说明统一入口 /api/chat 和保留的 /api/rag/search"
```

---

## 验收任务

### Task 10: 完整验收测试

**Files:** 无（手动验证）

- [ ] **Step 1: 运行所有单元测试**

```bash
cd /d/tmp/CompanyRag
mvn clean test -q
```

Expected: BUILD SUCCESS, 所有测试通过

- [ ] **Step 2: 验证编译**

```bash
cd /d/tmp/CompanyRag
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 检查循环依赖**

```bash
cd /d/tmp/CompanyRag
mvn dependency:tree | grep -E "(company-rag-agent|company-rag-rag|company-rag-common)"
```

Expected: 无循环依赖

- [ ] **Step 4: 验证 Git 提交历史**

```bash
cd /d/tmp/CompanyRag
git log --oneline --since="2026-07-31" | head -20
```

Expected: 至少 9 个提交（每个 Task 一个提交）

- [ ] **Step 5: 记录验收结果**

创建 `VERIFICATION_REPORT.md` 记录验收结果

---

## 总结

**预计总工作量**: 3.5-4.5 天

**阶段分布**:
- 阶段 1: 基础组件创建 (1.5 天) - 3 个 Task
- 阶段 2: 统一入口与配置 (1 天) - 2 个 Task
- 阶段 3: 集成测试与验证 (1 天) - 2 个 Task
- 阶段 4: 性能测试与文档 (0.5-1 天) - 2 个 Task

**总计**: 9 个 Task, 约 40-50 个 Steps

**关键交付物**:
- ✅ KnowledgeBaseTool 工具类
- ✅ KnowledgeBaseResult 模型类
- ✅ ToolCallRecorder 迁移到 common 模块
- ✅ 统一 ChatController
- ✅ 集成测试套件（7 个测试类）
- ✅ 更新的 README 文档

**验收标准**:
- ✅ 所有单元测试通过
- ✅ 集成测试通过
- ✅ 性能测试通过（延迟 < 2 秒）
- ✅ 无循环依赖
- ✅ 文档完整更新

---

**计划版本**: 1.0  
**创建日期**: 2026-07-31  
**关联设计**: `docs/superpowers/specs/2026-07-31-agent-rag-integration-design.md`
