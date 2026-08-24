package com.company.rag.rag.config;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 工具配置
 * 使用 AggregatedToolCallbackProvider 统一管理所有工具 (包括 MCP 工具)
 */
@Configuration
public class AgentToolConfig {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(AggregatedToolCallbackProvider aggregatedProvider) {
        // 使用聚合提供者，自动包含所有 AgentToolRegistry 中的工具 (含 MCP 工具)
        return aggregatedProvider;
    }
}
