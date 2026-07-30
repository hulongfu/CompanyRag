package com.company.rag.web.controller;

import com.company.rag.common.model.R;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ChatController 单元测试
 * 测试 /api/chat 端点
 */
@WebMvcTest(controllers = ChatController.class)
@ContextConfiguration(classes = ChatControllerTest.TestConfig.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChatRouter chatRouter;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testChatEndpoint_Success() throws Exception {
        // 准备测试数据
        ChatRequest request = ChatRequest.builder()
                .query("什么是 RAG？")
                .tenantId(1L)
                .sessionId("session-123")
                .topK(10)
                .enableRerank(true)
                .includeDebug(false)
                .build();

        ChatResponse response = ChatResponse.builder()
                .answer("RAG 是检索增强生成（Retrieval-Augmented Generation）的缩写...")
                .sources(List.of("doc-1", "doc-2"))
                .build();

        // Mock ChatRouter 的行为
        given(chatRouter.route(any(ChatRequest.class))).willReturn(response);

        // 执行请求并验证响应
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.answer").value("RAG 是检索增强生成（Retrieval-Augmented Generation）的缩写..."))
                .andExpect(jsonPath("$.data.sources[0]").value("doc-1"))
                .andExpect(jsonPath("$.data.sources[1]").value("doc-2"));
    }

    @Test
    void testChatEndpoint_EmptyQuery() throws Exception {
        // 准备测试数据 - 空查询
        ChatRequest request = ChatRequest.builder()
                .query("")
                .tenantId(1L)
                .build();

        ChatResponse response = ChatResponse.builder()
                .answer("请输入您的问题。")
                .sources(List.of())
                .build();

        // Mock ChatRouter 的行为
        given(chatRouter.route(any(ChatRequest.class))).willReturn(response);

        // 执行请求并验证响应
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("请输入您的问题。"))
                .andExpect(jsonPath("$.data.sources").isArray())
                .andExpect(jsonPath("$.data.sources").isEmpty());
    }

    @Test
    void testChatEndpoint_WithDebugInfo() throws Exception {
        // 准备测试数据 - 包含调试信息
        ChatRequest request = ChatRequest.builder()
                .query("如何配置数据库？")
                .tenantId(1L)
                .includeDebug(true)
                .build();

        ChatResponse response = ChatResponse.builder()
                .answer("配置数据库需要以下步骤...")
                .sources(List.of("db-doc-1"))
                .build();

        // Mock ChatRouter 的行为
        given(chatRouter.route(any(ChatRequest.class))).willReturn(response);

        // 执行请求并验证响应
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.answer").value("配置数据库需要以下步骤..."))
                .andExpect(jsonPath("$.data.sources[0]").value("db-doc-1"));
    }

    /**
     * 测试配置类
     * 仅导入必要的配置，排除依赖外部 bean 的配置
     */
    @Import({ChatController.class})
    static class TestConfig {
    }
}
