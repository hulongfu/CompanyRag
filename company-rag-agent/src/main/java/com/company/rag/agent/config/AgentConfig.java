package com.company.rag.agent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import com.company.rag.agent.tool.AgentToolRegistry;

/**
 * Agent 配置类
 * 配置 Spring AI Alibaba ReactAgent 和 Skill Registry
 */
@Configuration
public class AgentConfig {

    /**
     * 配置 ReactAgent Bean
     * ReactAgent 提供 ReAct（Reasoning + Acting）模式的 Agent 实现
     * 可自主分析用户问题，决定调用 Skill 或 Tool
     * 
     * @param chatModel ChatModel 用于 LLM 对话
     * @param toolCallbackProvider Tool 回调提供者
     * @return ReactAgent 实例
     */
    @Bean
    @Description("ReactAgent for autonomous tool and skill invocation")
    public org.springframework.ai.agent.ReactAgent reactAgent(
            ChatModel chatModel,
            ToolCallbackProvider toolCallbackProvider) {
        
        return org.springframework.ai.agent.ReactAgent.builder()
                .chatModel(chatModel)
                .toolCallbacks(toolCallbackProvider)
                .build();
    }

    /**
     * 配置 ToolCallbackProvider
     * 将 AgentToolRegistry 中的工具注册为可调用的 Tool
     * 
     * @param toolRegistry AgentToolRegistry 实例
     * @return ToolCallbackProvider 实例
     */
    @Bean
    public ToolCallbackProvider toolCallbackProvider(AgentToolRegistry toolRegistry) {
        // 使用 MethodToolCallbackProvider 将 AgentToolRegistry 中的方法注册为 Tool
        return MethodToolCallbackProvider.builder()
                .toolObjects(toolRegistry)
                .build();
    }
}
