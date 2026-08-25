package com.company.rag.agent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

/**
 * Agent 配置类
 * 配置 Spring AI Alibaba ReactAgent
 * 
 * 注意：ToolCallbackProvider 由 company-rag-rag 模块的 AgentToolConfig 统一提供，
 * 使用 AggregatedToolCallbackProvider 聚合所有工具（包括 MCP 工具）
 */
@Configuration
public class AgentConfig {

    /**
     * 配置 ReactAgent Bean
     * ReactAgent 提供 ReAct（Reasoning + Acting）模式的 Agent 实现
     * 可自主分析用户问题，决定调用 Skill 或 Tool
     * 
     * @param chatModel ChatModel 用于 LLM 对话
     * @param toolCallbackProvider Tool 回调提供者（由 AgentToolConfig 提供）
     * @return ReactAgent 实例
     */
    @Bean
    @Description("ReactAgent for autonomous tool and skill invocation")
    public com.alibaba.cloud.ai.graph.agent.ReactAgent reactAgent(
            ChatModel chatModel,
            ToolCallbackProvider toolCallbackProvider) {
        
        return com.alibaba.cloud.ai.graph.agent.ReactAgent.builder()
                .name("rag-agent")
                .model(chatModel)
                .toolCallbackProviders(toolCallbackProvider)
                .build();
    }
}
