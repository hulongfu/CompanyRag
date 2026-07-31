package com.company.rag.rag.config;

import com.company.rag.agent.tool.ApiDocTool;
import com.company.rag.agent.tool.CodeSearchTool;
import com.company.rag.agent.tool.DatabaseQueryTool;
import com.company.rag.rag.tools.KnowledgeBaseTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 工具配置
 * 将所有@Tool 组件注册到 ToolCallbackProvider，供 ChatClient 使用
 */
@Configuration
public class AgentToolConfig {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(DatabaseQueryTool databaseQueryTool,
                                                      ApiDocTool apiDocTool,
                                                      CodeSearchTool codeSearchTool,
                                                      KnowledgeBaseTool knowledgeBaseTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(databaseQueryTool, apiDocTool, codeSearchTool, knowledgeBaseTool)
                .build();
    }
}
