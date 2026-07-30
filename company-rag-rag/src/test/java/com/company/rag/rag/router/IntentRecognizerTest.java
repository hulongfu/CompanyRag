package com.company.rag.rag.router;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * IntentRecognizer 测试类
 * 验证意图识别流程
 */
class IntentRecognizerTest {

    @Mock
    private ChatModel chatModel;

    private IntentRecognizer recognizer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        recognizer = new IntentRecognizer(chatModel);
    }

    @Test
    void testRecognizeDatabaseIntent() {
        // 测试数据库意图识别
        IntentResult result = recognizer.recognize("如何查询数据库中的用户信息");
        
        assertNotNull(result);
        assertEquals(IntentType.DATABASE, result.getIntent());
        assertEquals("rule", result.getSource());
        assertTrue(result.getConfidence() >= 0.8);
    }

    @Test
    void testRecognizeCodeIntent() {
        // 测试代码意图识别
        IntentResult result = recognizer.recognize("这段代码怎么写");
        
        assertNotNull(result);
        assertEquals(IntentType.CODE, result.getIntent());
        assertEquals("rule", result.getSource());
        assertTrue(result.getConfidence() >= 0.8);
    }

    @Test
    void testRecognizeChatIntent() {
        // 测试聊天意图识别
        IntentResult result = recognizer.recognize("你好，请问在吗");
        
        assertNotNull(result);
        assertEquals(IntentType.CHAT, result.getIntent());
        assertEquals("rule", result.getSource());
        assertTrue(result.getConfidence() >= 0.8);
    }

    @Test
    void testRecognizeDefaultDocumentIntent() {
        // 测试无法匹配规则时的默认意图
        IntentResult result = recognizer.recognize("一些无法匹配的查询内容");
        
        assertNotNull(result);
        assertEquals(IntentType.DOCUMENT, result.getIntent());
        assertEquals("default", result.getSource());
    }

    @Test
    void testRecognizeByLLM() {
        // 准备 LLM 响应
        String llmResponse = "INTENT: DATABASE\nCONFIDENCE: 0.85";
        
        // Mock ChatResponse 和 Generation 链
        AssistantMessage assistantMessage = new AssistantMessage(llmResponse);
        Generation generation = new Generation(assistantMessage);
        ChatResponse chatResponse = new ChatResponse(java.util.List.of(generation));
        
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        // 测试 LLM 意图识别
        IntentResult result = recognizer.recognizeByLLM("帮我分析一下数据结构");
        
        assertNotNull(result);
        assertEquals(IntentType.DATABASE, result.getIntent());
        assertEquals("llm", result.getSource());
        assertEquals(0.85, result.getConfidence());
    }

    @Test
    void testParseIntentResponse() {
        // 测试解析 LLM 响应
        String response = "INTENT: CODE\nCONFIDENCE: 0.9";
        IntentResult result = recognizer.parseIntentResponse(response);
        
        assertNotNull(result);
        assertEquals(IntentType.CODE, result.getIntent());
        assertEquals("llm", result.getSource());
        assertEquals(0.9, result.getConfidence());
    }

    @Test
    void testParseIntentResponseInvalid() {
        // 测试解析无效的 LLM 响应
        String response = "无效的响应格式";
        IntentResult result = recognizer.parseIntentResponse(response);
        
        assertNotNull(result);
        assertEquals(IntentType.DOCUMENT, result.getIntent());
        assertEquals("llm", result.getSource());
    }

    @Test
    void testRecognizeWithLLMFallback() {
        // 模拟规则匹配失败，LLM 成功识别
        // 由于规则优先，这里测试一个规则不匹配但 LLM 能识别的场景
        // 注意：实际实现中，规则匹配会优先返回，不会调用 LLM
        // 这个测试主要验证 LLM 识别功能
        String llmResponse = "INTENT: CHAT\nCONFIDENCE: 0.88";
        
        // Mock ChatResponse 和 Generation 链
        AssistantMessage assistantMessage = new AssistantMessage(llmResponse);
        Generation generation = new Generation(assistantMessage);
        ChatResponse chatResponse = new ChatResponse(java.util.List.of(generation));
        
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        IntentResult result = recognizer.recognizeByLLM("随便聊聊");
        
        assertNotNull(result);
        assertEquals(IntentType.CHAT, result.getIntent());
        assertEquals("llm", result.getSource());
        assertEquals(0.88, result.getConfidence());
    }
}
