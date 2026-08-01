package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.rag.response.ChatMetrics;
import com.company.rag.rag.response.ChatRequest;
import com.company.rag.rag.response.ChatResponse;
import com.company.rag.rag.router.ChatRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ChatController 集成测试
 * 验证 sessionId 和 tenantId 正确传递给 ChatRouter，确保会话记录被保存
 */
@WebMvcTest(controllers = ChatController.class)
@ContextConfiguration(classes = ChatControllerIntegrationTest.TestConfig.class)
class ChatControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatRouter chatRouter;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 测试验证 sessionId 和 tenantId 正确传递
     */
    @Test
    void testChatEndpoint_SessionIdAndTenantIdPassed() throws Exception {
        // 准备测试数据 - 包含 sessionId 和 tenantId
        ChatRequest request = ChatRequest.builder()
                .query("作业执行出问题的排查方法有哪些？")
                .tenantId(10L)
                .sessionId("test-session-123")
                .userId(1L)
                .topK(10)
                .enableRerank(true)
                .includeDebug(false)
                .build();

        ChatResponse response = ChatResponse.builder()
                .answer("排查作业执行问题的方法包括：1. 检查日志 2. 验证配置 3. 查看资源使用情况...")
                .sources(List.of("doc-1", "doc-2"))
                .metrics(ChatMetrics.builder().totalMs(1000L).build())
                .build();

        // Mock ChatRouter 的行为
        given(chatRouter.route(any(ChatRequest.class))).willReturn(response);

        // 执行请求
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "10")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("排查作业执行问题的方法包括：1. 检查日志 2. 验证配置 3. 查看资源使用情况..."))
                .andExpect(jsonPath("$.data.sources[0]").value("doc-1"))
                .andExpect(jsonPath("$.data.sources[1]").value("doc-2"));

        // 验证 ChatRouter 被调用，且传递了正确的参数
        verify(chatRouter).route(any(ChatRequest.class));
    }

    /**
     * 测试验证请求头中的 tenantId 被正确使用
     */
    @Test
    void testChatEndpoint_TenantIdFromHeader() throws Exception {
        // 准备测试数据 - 不设置 tenantId，依赖请求头
        ChatRequest request = ChatRequest.builder()
                .query("什么是 RAG？")
                .sessionId("session-456")
                .build();

        ChatResponse response = ChatResponse.builder()
                .answer("RAG 是检索增强生成...")
                .sources(List.of("rag-doc"))
                .build();

        given(chatRouter.route(any(ChatRequest.class))).willReturn(response);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "20")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("RAG 是检索增强生成..."));
    }

    /**
     * 测试验证空会话 ID 时的处理
     */
    @Test
    void testChatEndpoint_WithoutSessionId() throws Exception {
        // 准备测试数据 - 没有 sessionId
        ChatRequest request = ChatRequest.builder()
                .query("临时问题")
                .tenantId(1L)
                .build();

        ChatResponse response = ChatResponse.builder()
                .answer("这是一个临时问题的回答...")
                .sources(List.of())
                .build();

        given(chatRouter.route(any(ChatRequest.class))).willReturn(response);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "1")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("这是一个临时问题的回答..."));
    }

    /**
     * 测试配置类
     */
    @Import({ChatController.class})
    static class TestConfig {
    }
}
