package com.company.rag.rag.router;

import com.company.rag.agent.service.RagAgentService;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.response.ChatRequest;
import com.company.rag.rag.service.RagSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ChatRouter 测试类
 * 验证路由逻辑和降级策略
 */
class ChatRouterTest {

    @Mock
    private IntentRecognizer intentRecognizer;

    @Mock
    private RagSearchService ragSearchService;

    @Mock
    private RagAgentService ragAgentService;

    @Mock
    private ChatModel chatModel;

    private ChatRouter chatRouter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        chatRouter = new ChatRouter(intentRecognizer, ragSearchService, ragAgentService, chatModel);
    }

    @Test
    void testDocumentIntent() {
        // 测试 DOCUMENT 意图路由

        // 准备 Mock 数据
        String query = "公司的人力资源政策是什么";
        ChatRequest request = ChatRequest.builder()
                .query(query)
                .tenantId(1L)
                .sessionId("session-1")
                .topK(10)
                .enableRerank(true)
                .includeDebug(false)
                .build();

        // Mock 意图识别结果
        IntentResult intentResult = IntentResult.builder()
                .intent(IntentType.DOCUMENT)
                .source("rule")
                .confidence(0.9)
                .build();
        when(intentRecognizer.recognize(query)).thenReturn(intentResult);

        // Mock RAG 搜索结果
        RagResult ragResult = new RagResult();
        ragResult.setAnswer("根据公司人力资源政策文档...");
        ragResult.setSessions(List.of("人力资源政策.pdf (第 1 段)", "人力资源政策.pdf (第 3 段)"));

        when(ragSearchService.search(any(RagQuery.class))).thenReturn(ragResult);

        // 执行测试
        com.company.rag.rag.response.ChatResponse response = chatRouter.route(request);

        // 验证结果
        assertNotNull(response);
        assertEquals("根据公司人力资源政策文档...", response.getAnswer());
        assertEquals(2, response.getSources().size());
        assertEquals("人力资源政策.pdf (第 1 段)", response.getSources().get(0));
        assertEquals("人力资源政策.pdf (第 3 段)", response.getSources().get(1));
        assertNotNull(response.getMetrics());
        assertEquals(IntentType.DOCUMENT, response.getMetrics().getIntent());
        assertTrue(response.getMetrics().getTotalMs() >= 0);

        // 验证调用
        verify(intentRecognizer, times(1)).recognize(query);
        verify(ragSearchService, times(1)).search(any(RagQuery.class));
        verify(ragAgentService, never()).process(anyString(), anyString());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void testDatabaseIntent() {
        // 测试 DATABASE 意图路由

        String query = "查询用户表的结构";
        ChatRequest request = ChatRequest.builder()
                .query(query)
                .tenantId(1L)
                .build();

        // Mock 意图识别结果
        IntentResult intentResult = IntentResult.builder()
                .intent(IntentType.DATABASE)
                .source("rule")
                .confidence(0.9)
                .build();
        when(intentRecognizer.recognize(query)).thenReturn(intentResult);

        // Mock Agent 服务返回
        String agentAnswer = "用户表包含以下字段：id, username, email, created_at...";
        when(ragAgentService.process(eq(query), anyString())).thenReturn(agentAnswer);

        // 执行测试
        com.company.rag.rag.response.ChatResponse response = chatRouter.route(request);

        // 验证结果
        assertNotNull(response);
        assertEquals("用户表包含以下字段：id, username, email, created_at...", response.getAnswer());
        assertEquals(1, response.getSources().size());
        assertEquals("agent:database", response.getSources().get(0));
        assertNotNull(response.getMetrics());
        assertEquals(IntentType.DATABASE, response.getMetrics().getIntent());

        // 验证调用
        verify(intentRecognizer, times(1)).recognize(query);
        verify(ragAgentService, times(1)).process(eq(query), anyString());
        verify(ragSearchService, never()).search(any(RagQuery.class));
    }

    @Test
    void testFallbackOnException() {
        // 测试异常降级

        String query = "测试查询";
        ChatRequest request = ChatRequest.builder()
                .query(query)
                .tenantId(1L)
                .includeDebug(true)  // 包含调试信息以便验证
                .build();

        // Mock 意图识别抛出异常
        when(intentRecognizer.recognize(query)).thenThrow(new RuntimeException("意图识别服务异常"));

        // Mock LLM 直接回答
        String llmAnswer = "抱歉，我无法回答这个问题。";
        AssistantMessage assistantMessage = new AssistantMessage(llmAnswer);
        Generation generation = new Generation(assistantMessage);
        org.springframework.ai.chat.model.ChatResponse springChatResponse = new org.springframework.ai.chat.model.ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(springChatResponse);

        // 执行测试
        com.company.rag.rag.response.ChatResponse response = chatRouter.route(request);

        // 验证结果（应该降级处理）
        assertNotNull(response);
        assertNotNull(response.getAnswer());
        assertNotNull(response.getMetrics());
        // 验证调试信息中的 recognizeSource 包含 fallback 标记
        assertNotNull(response.getDebug());
        assertTrue(response.getDebug().getRecognizeSource().contains("fallback"));

        // 验证调用（意图识别失败后应该降级）
        verify(intentRecognizer, times(1)).recognize(query);
    }

    @Test
    void testChatIntent() {
        // 测试 CHAT 意图路由

        String query = "你好，请问在吗";
        ChatRequest request = ChatRequest.builder()
                .query(query)
                .build();

        // Mock 意图识别结果
        IntentResult intentResult = IntentResult.builder()
                .intent(IntentType.CHAT)
                .source("rule")
                .confidence(0.85)
                .build();
        when(intentRecognizer.recognize(query)).thenReturn(intentResult);

        // Mock LLM 回答
        String llmAnswer = "你好！我在，有什么可以帮助你的吗？";
        AssistantMessage assistantMessage = new AssistantMessage(llmAnswer);
        Generation generation = new Generation(assistantMessage);
        org.springframework.ai.chat.model.ChatResponse springChatResponse = new org.springframework.ai.chat.model.ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(springChatResponse);

        // 执行测试
        com.company.rag.rag.response.ChatResponse response = chatRouter.route(request);

        // 验证结果
        assertNotNull(response);
        assertEquals("你好！我在，有什么可以帮助你的吗？", response.getAnswer());
        assertEquals(1, response.getSources().size());
        assertEquals("llm-direct", response.getSources().get(0));
        assertNotNull(response.getMetrics());
        assertEquals(IntentType.CHAT, response.getMetrics().getIntent());

        // 验证调用
        verify(intentRecognizer, times(1)).recognize(query);
        verify(chatModel, times(1)).call(any(Prompt.class));
        verify(ragSearchService, never()).search(any(RagQuery.class));
        verify(ragAgentService, never()).process(anyString(), anyString());
    }

    @Test
    void testAgentFallbackToRag() {
        // 测试 Agent 失败降级到 RAG

        String query = "查询数据库中的订单信息";
        ChatRequest request = ChatRequest.builder()
                .query(query)
                .tenantId(1L)
                .build();

        // Mock 意图识别结果
        IntentResult intentResult = IntentResult.builder()
                .intent(IntentType.DATABASE)
                .source("rule")
                .confidence(0.9)
                .build();
        when(intentRecognizer.recognize(query)).thenReturn(intentResult);

        // Mock Agent 抛出异常
        when(ragAgentService.process(eq(query), anyString())).thenThrow(new RuntimeException("Agent 服务异常"));

        // Mock RAG 搜索结果（作为降级）
        RagResult ragResult = new RagResult();
        ragResult.setAnswer("订单表包含订单号、金额、状态等字段...");
        ragResult.setSessions(Collections.singletonList("数据库文档.pdf"));
        when(ragSearchService.search(any(RagQuery.class))).thenReturn(ragResult);

        // 执行测试
        com.company.rag.rag.response.ChatResponse response = chatRouter.route(request);

        // 验证结果（应该从 RAG 降级获得结果）
        assertNotNull(response);
        assertEquals("订单表包含订单号、金额、状态等字段...", response.getAnswer());
        assertEquals(1, response.getSources().size());

        // 验证调用
        verify(intentRecognizer, times(1)).recognize(query);
        verify(ragAgentService, times(1)).process(eq(query), anyString());
        verify(ragSearchService, times(1)).search(any(RagQuery.class));
    }

    @Test
    void testRagFallbackToLlm() {
        // 测试 RAG 失败降级到 LLM

        String query = "公司政策是什么";
        ChatRequest request = ChatRequest.builder()
                .query(query)
                .tenantId(1L)
                .build();

        // Mock 意图识别结果
        IntentResult intentResult = IntentResult.builder()
                .intent(IntentType.DOCUMENT)
                .source("default")
                .confidence(0.5)
                .build();
        when(intentRecognizer.recognize(query)).thenReturn(intentResult);

        // Mock RAG 搜索抛出异常
        when(ragSearchService.search(any(RagQuery.class))).thenThrow(new RuntimeException("RAG 服务异常"));

        // Mock LLM 回答
        String llmAnswer = "根据公司的一般政策...";
        AssistantMessage assistantMessage = new AssistantMessage(llmAnswer);
        Generation generation = new Generation(assistantMessage);
        org.springframework.ai.chat.model.ChatResponse springChatResponse = new org.springframework.ai.chat.model.ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(springChatResponse);

        // 执行测试
        com.company.rag.rag.response.ChatResponse response = chatRouter.route(request);

        // 验证结果（应该从 LLM 降级获得结果）
        assertNotNull(response);
        assertEquals("根据公司的一般政策...", response.getAnswer());
        assertEquals(1, response.getSources().size());
        assertEquals("llm-direct", response.getSources().get(0));

        // 验证调用
        verify(intentRecognizer, times(1)).recognize(query);
        verify(ragSearchService, times(1)).search(any(RagQuery.class));
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void testDebugInfoIncluded() {
        // 测试调试信息包含

        String query = "测试调试信息";
        ChatRequest request = ChatRequest.builder()
                .query(query)
                .tenantId(1L)
                .includeDebug(true)
                .build();

        // Mock 意图识别结果
        IntentResult intentResult = IntentResult.builder()
                .intent(IntentType.CHAT)
                .source("rule")
                .confidence(0.85)
                .build();
        when(intentRecognizer.recognize(query)).thenReturn(intentResult);

        // Mock LLM 回答
        String llmAnswer = "测试回答";
        AssistantMessage assistantMessage = new AssistantMessage(llmAnswer);
        Generation generation = new Generation(assistantMessage);
        org.springframework.ai.chat.model.ChatResponse springChatResponse = new org.springframework.ai.chat.model.ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(springChatResponse);

        // 执行测试
        com.company.rag.rag.response.ChatResponse response = chatRouter.route(request);

        // 验证调试信息
        assertNotNull(response.getDebug());
        assertEquals(IntentType.CHAT, response.getDebug().getIntent());
        assertEquals("rule", response.getDebug().getRecognizeSource());
        assertEquals(0.85, response.getDebug().getConfidence());
        assertNotNull(response.getDebug().getRoutePath());
    }

    @Test
    void testGetHandlerName() {
        // 测试获取处理器名称

        assertEquals("DocumentHandler", chatRouter.getHandlerName(IntentType.DOCUMENT));
        assertEquals("DatabaseAgentHandler", chatRouter.getHandlerName(IntentType.DATABASE));
        assertEquals("CodeAgentHandler", chatRouter.getHandlerName(IntentType.CODE));
        assertEquals("ChatHandler", chatRouter.getHandlerName(IntentType.CHAT));
    }

    @Test
    void testCodeIntent() {
        // 测试 CODE 意图路由

        String query = "这段 Java 代码怎么写";
        ChatRequest request = ChatRequest.builder()
                .query(query)
                .tenantId(1L)
                .build();

        // Mock 意图识别结果
        IntentResult intentResult = IntentResult.builder()
                .intent(IntentType.CODE)
                .source("rule")
                .confidence(0.85)
                .build();
        when(intentRecognizer.recognize(query)).thenReturn(intentResult);

        // Mock Agent 服务返回
        String agentAnswer = "可以使用以下代码实现：public class Example { ... }";
        when(ragAgentService.process(eq(query), anyString())).thenReturn(agentAnswer);

        // 执行测试
        com.company.rag.rag.response.ChatResponse response = chatRouter.route(request);

        // 验证结果
        assertNotNull(response);
        assertEquals("可以使用以下代码实现：public class Example { ... }", response.getAnswer());
        assertEquals(1, response.getSources().size());
        assertEquals("agent:code", response.getSources().get(0));
        assertEquals(IntentType.CODE, response.getMetrics().getIntent());

        // 验证调用
        verify(intentRecognizer, times(1)).recognize(query);
        verify(ragAgentService, times(1)).process(eq(query), anyString());
    }
}
