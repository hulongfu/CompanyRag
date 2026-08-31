package com.company.rag.agent.executor;

import com.company.rag.agent.service.AgentResult;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 流式 Agent 执行器
 * 
 * 封装 ReactAgent 的调用，提供：
 * 1. 统一的调用接口
 * 2. 增强的错误处理和日志记录
 * 3. 为未来流式支持预留接口
 * 
 * 注意：当前 ReactAgent 不支持流式 API，使用非流式调用。
 * 真正的流式支持需要在 Spring AI Alibaba 层面实现。
 * 
 * @author AI Assistant
 * @since 2026-08-30
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingAgentExecutor {
    
    private final ReactAgent reactAgent;
    
    /**
     * 执行 Agent 调用（使用 ReactAgent）
     * 
     * @param messages 消息列表
     * @return Agent 执行结果
     * @throws GraphRunnerException Agent 执行异常
     */
    public AgentResult execute(List<Message> messages) throws GraphRunnerException {
        log.info("[AGENT-EXEC] 开始执行 Agent 调用");
        
        try {
            AssistantMessage response = reactAgent.call(messages);
            String content = response != null ? response.getText() : "";
            
            log.info("[AGENT-EXEC] Agent 调用完成，响应长度={}", content.length());
            
            return new AgentResult(content, null);
        } catch (GraphRunnerException e) {
            log.error("[AGENT-EXEC] Agent 执行失败 | error={}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("[AGENT-EXEC] Agent 调用异常 | error={}", e.getMessage(), e);
            throw new GraphRunnerException("Agent 调用失败：" + e.getMessage(), e);
        }
    }
}
