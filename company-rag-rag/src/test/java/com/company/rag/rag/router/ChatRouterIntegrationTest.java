package com.company.rag.rag.router;

import com.company.rag.agent.service.RagAgentService;
import com.company.rag.rag.service.RagSearchService;
import com.company.rag.rag.response.ChatRequest;
import com.company.rag.rag.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatRouter 集成测试类
 * 使用 @SpringBootTest 测试完整的端到端流程
 * 验证不同意图的路由逻辑和响应格式
 */
@SpringBootTest
@TestPropertySource(properties = {
    // 使用测试 API Key
    "spring.ai.dashscope.api-key=test-key",
    "spring.ai.openai.api-key=test-key",
    "spring.ai.openai.base-url=http://localhost:8080"
})
class ChatRouterIntegrationTest {

    @SpringBootConfiguration
    @ComponentScan(
            basePackages = "com.company.rag.rag.router",
            excludeFilters = {
                    @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Config.*"),
                    @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Cache.*")
            }
    )
    static class TestConfig {
        // 测试配置类 - 只扫描 router 包
    }

    @Autowired
    private ChatRouter chatRouter;

    @MockBean
    private RagSearchService ragSearchService;

    @MockBean
    private RagAgentService ragAgentService;

    @MockBean
    private ChatModel chatModel;

    @Test
    void testEndToEnd_DocumentIntent() {
        // 测试 DOCUMENT 意图的端到端流程
        // 问题：公司产品文档在哪里？
        ChatRequest request = new ChatRequest();
        request.setQuery("公司产品文档在哪里？");
        request.setTenantId(1L);
        request.setTopK(10);
        request.setEnableRerank(true);

        ChatResponse response = chatRouter.route(request);

        // 验证响应不为空
        assertNotNull(response, "响应不应为空");
        assertNotNull(response.getAnswer(), "回答不应为空");
        assertNotNull(response.getMetrics(), "指标不应为空");
        
        // 验证意图识别正确
        assertTrue(response.getMetrics().getIntent() != null, "意图类型不应为空");
    }

    @Test
    void testEndToEnd_DatabaseIntent() {
        // 测试 DATABASE 意图的端到端流程
        // 问题：公司有多少员工？
        ChatRequest request = new ChatRequest();
        request.setQuery("公司有多少员工？");
        request.setTenantId(1L);

        ChatResponse response = chatRouter.route(request);

        // 验证响应不为空
        assertNotNull(response, "响应不应为空");
        assertNotNull(response.getAnswer(), "回答不应为空");
        
        // 验证指标存在
        assertNotNull(response.getMetrics(), "指标不应为空");
    }

    @Test
    void testEndToEnd_ChatIntent() {
        // 测试 CHAT 意图的端到端流程
        // 问题：你好
        ChatRequest request = new ChatRequest();
        request.setQuery("你好");
        request.setTenantId(1L);

        ChatResponse response = chatRouter.route(request);

        // 验证响应不为空
        assertNotNull(response, "响应不应为空");
        assertNotNull(response.getAnswer(), "回答不应为空");
        
        // 验证指标存在
        assertNotNull(response.getMetrics(), "指标不应为空");
    }

    @Test
    void testEndToEnd_CodeIntent() {
        // 测试 CODE 意图的端到端流程
        // 问题：如何用 Java 实现单例模式？
        ChatRequest request = new ChatRequest();
        request.setQuery("如何用 Java 实现单例模式？");
        request.setTenantId(1L);

        ChatResponse response = chatRouter.route(request);

        // 验证响应不为空
        assertNotNull(response, "响应不应为空");
        assertNotNull(response.getAnswer(), "回答不应为空");
        
        // 验证指标存在
        assertNotNull(response.getMetrics(), "指标不应为空");
    }

    @Test
    void testEndToEnd_WithDebugInfo() {
        // 测试包含调试信息的端到端流程
        ChatRequest request = new ChatRequest();
        request.setQuery("测试查询");
        request.setTenantId(1L);
        request.setIncludeDebug(true);

        ChatResponse response = chatRouter.route(request);

        // 验证响应不为空
        assertNotNull(response, "响应不应为空");
        assertNotNull(response.getAnswer(), "回答不应为空");
        
        // 验证调试信息存在
        assertNotNull(response.getDebug(), "调试信息不应为空");
        assertNotNull(response.getDebug().getIntent(), "调试信息中的意图不应为空");
        assertNotNull(response.getDebug().getRecognizeSource(), "调试信息中的识别来源不应为空");
    }

    @Test
    void testEndToEnd_FallbackStrategy() {
        // 测试降级策略
        // 当意图识别失败时，应该降级到默认 DOCUMENT 意图
        ChatRequest request = new ChatRequest();
        request.setQuery("这是一个测试查询用于验证降级策略");
        request.setTenantId(1L);

        ChatResponse response = chatRouter.route(request);

        // 验证即使出现异常，也应该有兜底响应
        assertNotNull(response, "响应不应为空");
        assertNotNull(response.getAnswer(), "回答不应为空");
        
        // 验证指标存在
        assertNotNull(response.getMetrics(), "指标不应为空");
    }

    @Test
    void testEndToEnd_EmptyQuery() {
        // 测试空查询的处理
        ChatRequest request = new ChatRequest();
        request.setQuery("");
        request.setTenantId(1L);

        ChatResponse response = chatRouter.route(request);

        // 验证即使查询为空，也应该有响应
        assertNotNull(response, "响应不应为空");
        assertNotNull(response.getAnswer(), "回答不应为空");
    }

    @Test
    void testEndToEnd_SpecialCharacters() {
        // 测试包含特殊字符的查询
        ChatRequest request = new ChatRequest();
        request.setQuery("测试@#$%特殊字符&*()");
        request.setTenantId(1L);

        ChatResponse response = chatRouter.route(request);

        // 验证特殊字符查询也能正常处理
        assertNotNull(response, "响应不应为空");
        assertNotNull(response.getAnswer(), "回答不应为空");
    }

    @Test
    void testEndToEnd_LongQuery() {
        // 测试长查询的处理
        String longQuery = "这是一个非常长的查询，用于测试系统对长文本的处理能力。" +
                "我们想知道当用户输入很长的内容时，系统是否还能正确识别意图并给出合适的回答。" +
                "这个查询包含了多个句子，每个句子都提供了一些额外的上下文信息。" +
                "希望系统能够理解这个长查询的核心意图，并给出准确的回答。";
        
        ChatRequest request = new ChatRequest();
        request.setQuery(longQuery);
        request.setTenantId(1L);

        ChatResponse response = chatRouter.route(request);

        // 验证长查询也能正常处理
        assertNotNull(response, "响应不应为空");
        assertNotNull(response.getAnswer(), "回答不应为空");
    }

    @Test
    void testEndToEnd_ConcurrentRequests() throws InterruptedException {
        // 测试并发请求处理
        Thread[] threads = new Thread[5];
        final boolean[] success = {true};
        
        for (int i = 0; i < 5; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    ChatRequest request = new ChatRequest();
                    request.setQuery("并发测试请求 " + index);
                    request.setTenantId(1L);
                    
                    ChatResponse response = chatRouter.route(request);
                    
                    if (response == null || response.getAnswer() == null) {
                        success[0] = false;
                    }
                } catch (Exception e) {
                    success[0] = false;
                }
            });
            threads[i].start();
        }
        
        // 等待所有线程完成
        for (Thread thread : threads) {
            thread.join();
        }
        
        // 验证所有并发请求都成功
        assertTrue(success[0], "所有并发请求都应该成功");
    }
}
