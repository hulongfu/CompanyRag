package com.company.rag.rag.response;

import com.company.rag.rag.router.IntentResult;
import com.company.rag.rag.router.IntentType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatRequest 及相关类的测试
 */
class ChatRequestTest {

    @Test
    void testChatRequestBuilder() {
        // Given & When
        ChatRequest request = ChatRequest.builder()
                .query("测试问题")
                .sessionId("session-123")
                .tenantId(1L)
                .topK(5)
                .enableRerank(false)
                .includeDebug(true)
                .mode("search")
                .build();

        // Then
        assertEquals("测试问题", request.getQuery());
        assertEquals("session-123", request.getSessionId());
        assertEquals(1L, request.getTenantId());
        assertEquals(5, request.getTopK());
        assertFalse(request.getEnableRerank());
        assertTrue(request.getIncludeDebug());
        assertEquals("search", request.getMode());
    }

    @Test
    void testChatRequestDefaultValues() {
        // Given
        ChatRequest request = new ChatRequest();

        // Then
        assertEquals(10, request.getTopK());
        assertTrue(request.getEnableRerank());
        assertFalse(request.getIncludeDebug());
        assertNull(request.getQuery());
        assertNull(request.getSessionId());
        assertNull(request.getTenantId());
        assertNull(request.getMode());
    }

    @Test
    void testChatResponseBuilder() {
        // Given
        ChatMetrics metrics = ChatMetrics.builder()
                .totalMs(150L)
                .tokens(256)
                .intent(IntentType.DOCUMENT)
                .routePath("router -> retriever -> rerank -> llm")
                .build();

        DebugInfo debugInfo = DebugInfo.builder()
                .intent(IntentType.DOCUMENT)
                .recognizeSource("llm")
                .confidence(0.95)
                .toolUsed("vector-retriever")
                .routePath("router -> retriever")
                .sources(Arrays.asList("doc1", "doc2"))
                .build();

        List<String> sources = Arrays.asList("来源 1", "来源 2");

        // When
        ChatResponse response = ChatResponse.builder()
                .answer("这是回答")
                .sources(sources)
                .metrics(metrics)
                .debug(debugInfo)
                .build();

        // Then
        assertEquals("这是回答", response.getAnswer());
        assertEquals(2, response.getSources().size());
        assertNotNull(response.getMetrics());
        assertEquals(150L, response.getMetrics().getTotalMs());
        assertEquals(256, response.getMetrics().getTokens());
        assertEquals(IntentType.DOCUMENT, response.getMetrics().getIntent());
        assertNotNull(response.getDebug());
        assertEquals(0.95, response.getDebug().getConfidence());
        assertEquals("llm", response.getDebug().getRecognizeSource());
    }

    @Test
    void testChatMetricsBuilder() {
        // When
        ChatMetrics metrics = ChatMetrics.builder()
                .totalMs(200L)
                .tokens(512)
                .intent(IntentType.DATABASE)
                .routePath("db-router -> query")
                .build();

        // Then
        assertEquals(200L, metrics.getTotalMs());
        assertEquals(512, metrics.getTokens());
        assertEquals(IntentType.DATABASE, metrics.getIntent());
        assertEquals("db-router -> query", metrics.getRoutePath());
    }

    @Test
    void testDebugInfoBuilder() {
        // When
        DebugInfo debugInfo = DebugInfo.builder()
                .intent(IntentType.CODE)
                .recognizeSource("rule")
                .confidence(0.88)
                .toolUsed("code-search")
                .routePath("code-router")
                .build();

        // Then
        assertEquals(IntentType.CODE, debugInfo.getIntent());
        assertEquals("rule", debugInfo.getRecognizeSource());
        assertEquals(0.88, debugInfo.getConfidence());
        assertEquals("code-search", debugInfo.getToolUsed());
        assertEquals("code-router", debugInfo.getRoutePath());
    }

    @Test
    void testIntentResultBuilder() {
        // When
        IntentResult result = IntentResult.builder()
                .intent(IntentType.CHAT)
                .source("llm")
                .confidence(0.92)
                .build();

        // Then
        assertEquals(IntentType.CHAT, result.getIntent());
        assertEquals("llm", result.getSource());
        assertEquals(0.92, result.getConfidence());
    }

    @Test
    void testIntentTypeValues() {
        // Then
        assertEquals(4, IntentType.values().length);
        assertTrue(Arrays.asList(IntentType.values()).contains(IntentType.DOCUMENT));
        assertTrue(Arrays.asList(IntentType.values()).contains(IntentType.DATABASE));
        assertTrue(Arrays.asList(IntentType.values()).contains(IntentType.CODE));
        assertTrue(Arrays.asList(IntentType.values()).contains(IntentType.CHAT));
    }

    @Test
    void testChatResponseWithoutDebug() {
        // When
        ChatResponse response = ChatResponse.builder()
                .answer("简单回答")
                .sources(Arrays.asList("source1"))
                .build();

        // Then
        assertEquals("简单回答", response.getAnswer());
        assertEquals(1, response.getSources().size());
        assertNull(response.getMetrics());
        assertNull(response.getDebug());
    }

    @Test
    void testDebugInfoWithSources() {
        // Given
        List<String> sources = Arrays.asList("doc-1", "doc-2", "doc-3");

        // When
        DebugInfo debugInfo = DebugInfo.builder()
                .intent(IntentType.DOCUMENT)
                .recognizeSource("model")
                .confidence(0.99)
                .sources(sources)
                .build();

        // Then
        assertEquals(3, debugInfo.getSources().size());
        assertEquals("doc-1", debugInfo.getSources().get(0));
        assertEquals("doc-3", debugInfo.getSources().get(2));
    }
}
