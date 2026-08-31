package com.company.rag.agent.executor;

import com.company.rag.agent.service.AgentResult;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StreamingAgentExecutor 单元测试
 * 
 * 测试场景：
 * 1. 正常执行：Agent 成功处理请求
 * 2. 空响应：Agent 返回空字符串
 * 3. 异常处理：Agent 执行失败
 * 
 * @author AI Assistant
 * @since 2026-08-30
 */
@ExtendWith(MockitoExtension.class)
class StreamingAgentExecutorTest {
    
    @Mock
    private ReactAgent reactAgent;
    
    private StreamingAgentExecutor executor;
    
    @BeforeEach
    void setUp() {
        executor = new StreamingAgentExecutor(reactAgent);
    }
    
    /**
     * 测试场景 1：正常执行
     * 验证 Agent 成功处理请求并返回响应
     */
    @Test
    @SuppressWarnings("unchecked")
    void testExecute_Success() throws GraphRunnerException {
        // 准备测试数据
        String userInput = "请生成 API 文档";
        String expectedResponse = "这是 API 文档内容";
        List<Message> messages = List.of(new UserMessage(userInput));
        
        // Mock ReactAgent 行为
        AssistantMessage mockResponse = new AssistantMessage(expectedResponse);
        when(reactAgent.call(any(List.class))).thenReturn(mockResponse);
        
        // 执行测试
        AgentResult result = executor.execute(messages);
        
        // 验证结果
        assertNotNull(result);
        assertEquals(expectedResponse, result.getAnswer());
        assertNull(result.getToolContext());
        
        // 验证 ReactAgent 被调用
        verify(reactAgent, times(1)).call(anyList());
    }
    
    /**
     * 测试场景 2：空响应
     * 验证 Agent 返回空字符串时的处理
     */
    @Test
    @SuppressWarnings("unchecked")
    void testExecute_EmptyResponse() throws GraphRunnerException {
        // 准备测试数据
        String userInput = "测试问题";
        List<Message> messages = List.of(new UserMessage(userInput));
        
        // Mock ReactAgent 行为（返回空响应）
        AssistantMessage mockResponse = new AssistantMessage("");
        when(reactAgent.call(any(List.class))).thenReturn(mockResponse);
        
        // 执行测试
        AgentResult result = executor.execute(messages);
        
        // 验证结果
        assertNotNull(result);
        assertEquals("", result.getAnswer());
        
        // 验证 ReactAgent 被调用
        verify(reactAgent, times(1)).call(anyList());
    }
    
    /**
     * 测试场景 3：null 响应
     * 验证 Agent 返回 null 时的处理
     */
    @Test
    @SuppressWarnings("unchecked")
    void testExecute_NullResponse() throws GraphRunnerException {
        // 准备测试数据
        String userInput = "测试问题";
        List<Message> messages = List.of(new UserMessage(userInput));
        
        // Mock ReactAgent 行为（返回 null）
        when(reactAgent.call(any(List.class))).thenReturn(null);
        
        // 执行测试
        AgentResult result = executor.execute(messages);
        
        // 验证结果
        assertNotNull(result);
        assertEquals("", result.getAnswer());
        
        // 验证 ReactAgent 被调用
        verify(reactAgent, times(1)).call(anyList());
    }
    
    /**
     * 测试场景 4：GraphRunnerException 异常
     * 验证 Agent 执行失败时的异常传播
     */
    @Test
    @SuppressWarnings("unchecked")
    void testExecute_GraphRunnerException() throws GraphRunnerException {
        // 准备测试数据
        String userInput = "测试问题";
        List<Message> messages = List.of(new UserMessage(userInput));
        
        // Mock ReactAgent 行为（抛出异常）
        GraphRunnerException expectedException = new GraphRunnerException("Agent 执行失败");
        when(reactAgent.call(any(List.class))).thenThrow(expectedException);
        
        // 执行测试并验证异常
        GraphRunnerException exception = assertThrows(
            GraphRunnerException.class,
            () -> executor.execute(messages)
        );
        
        assertEquals("Agent 执行失败", exception.getMessage());
        
        // 验证 ReactAgent 被调用
        verify(reactAgent, times(1)).call(anyList());
    }
    
    /**
     * 测试场景 5：普通 Exception 异常
     * 验证其他异常被包装为 GraphRunnerException
     */
    @Test
    @SuppressWarnings("unchecked")
    void testExecute_GenericException() throws GraphRunnerException {
        // 准备测试数据
        String userInput = "测试问题";
        List<Message> messages = List.of(new UserMessage(userInput));
        
        // Mock ReactAgent 行为（抛出普通异常）
        RuntimeException expectedException = new RuntimeException("未知错误");
        when(reactAgent.call(any(List.class))).thenThrow(expectedException);
        
        // 执行测试并验证异常
        GraphRunnerException exception = assertThrows(
            GraphRunnerException.class,
            () -> executor.execute(messages)
        );
        
        assertTrue(exception.getMessage().contains("Agent 调用失败"));
        assertTrue(exception.getMessage().contains("未知错误"));
        
        // 验证 ReactAgent 被调用
        verify(reactAgent, times(1)).call(anyList());
    }
    
    /**
     * 测试场景 6：多轮对话历史
     * 验证带历史消息的执行
     */
    @Test
    @SuppressWarnings("unchecked")
    void testExecute_WithHistory() throws GraphRunnerException {
        // 准备测试数据
        Message history1 = new UserMessage("第一轮问题");
        Message history2 = new AssistantMessage("第一轮回答");
        Message currentMessage = new UserMessage("第二轮问题");
        List<Message> messages = List.of(history1, history2, currentMessage);
        
        String expectedResponse = "这是第二轮的回答";
        AssistantMessage mockResponse = new AssistantMessage(expectedResponse);
        when(reactAgent.call(any(List.class))).thenReturn(mockResponse);
        
        // 执行测试
        AgentResult result = executor.execute(messages);
        
        // 验证结果
        assertNotNull(result);
        assertEquals(expectedResponse, result.getAnswer());
        
        // 验证 ReactAgent 被调用（传入了完整的消息列表）
        verify(reactAgent, times(1)).call(argThat((List<Message> msgList) -> msgList.size() == 3));
    }
}
