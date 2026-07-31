package com.company.rag.agent.service;

import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.agent.tool.ApiDocTool;
import com.company.rag.agent.tool.CodeSearchTool;
import com.company.rag.agent.tool.DatabaseQueryTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG Agent 服务
 * 基于 Spring AI ChatClient 实现智能工具调用编排
 * 
 * Agent 模式工作流程：
 * 1. 用户提问 → ChatClient 分析意图
 * 2. ChatClient 自动决定是否需要调用工具（Function Calling）
 * 3. 如果需要：自动选择工具 → 执行 → 将结果反馈给 LLM
 * 4. LLM 基于工具结果生成最终回答
 * 5. 流式返回给用户
 */
@Slf4j
@Service
public class RagAgentService {

    private final ChatModel chatModel;
    private final ToolCallbackProvider toolCallbackProvider;
    private final AgentToolRegistry toolRegistry;
    
    private final ChatClient chatClient;

    /**
     * 构造方法，初始化 ChatClient 并注册工具
     */
    public RagAgentService(ChatModel chatModel, 
                           ToolCallbackProvider toolCallbackProvider,
                           AgentToolRegistry toolRegistry) {
        this.chatModel = chatModel;
        this.toolCallbackProvider = toolCallbackProvider;
        this.toolRegistry = toolRegistry;
        
        // 构建 ChatClient，注册工具回调
        this.chatClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(toolCallbackProvider)
                .build();
        
        // 调试日志：输出工具信息
        log.info("RagAgentService 初始化：chatModel={}, toolCallbackProvider={}", 
                 chatModel.getClass().getSimpleName(), 
                 toolCallbackProvider != null ? toolCallbackProvider.getClass().getSimpleName() : "null");
        
        if (toolCallbackProvider != null) {
            var callbacks = toolCallbackProvider.getToolCallbacks();
            log.info("注册的工具数量：{}", callbacks.length);
            for (var callback : callbacks) {
                log.info("  - 工具：{}, 描述：{}", 
                         callback.getToolDefinition().name(),
                         callback.getToolDefinition().description());
            }
        }
    }

    /**
     * 处理 Agent 请求，自动选择工具
     * @param userMessage 用户消息
     * @return Agent 处理结果（包含回答和工具上下文）
     */
    public AgentResult process(String userMessage) {
        log.info("收到 Agent 请求：{}", userMessage);
        
        try {
            // ChatClient 自动处理工具调用
            String response = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();
            
            log.info("Agent 处理完成，回答长度：{}", response != null ? response.length() : 0);
            return new AgentResult(response != null ? response : "", null);
            
        } catch (Exception e) {
            log.error("Agent 处理失败：{}", e.getMessage(), e);
            return new AgentResult("抱歉，系统繁忙，请稍后重试。", "error:" + e.getMessage());
        }
    }

    /**
     * 直接调用数据库查询工具（保留原有接口，供 AgentController 使用）
     */
    public String queryDatabase(String sql) {
        return toolRegistry.executeTool("database_query", Map.of("sql", sql));
    }

    /**
     * 直接调用代码搜索工具（保留原有接口，供 AgentController 使用）
     */
    public String searchCode(String keyword, String ext) {
        return toolRegistry.executeTool("code_search", Map.of("keyword", keyword, "ext", ext));
    }

    /**
     * 直接调用 API 文档工具（保留原有接口，供 AgentController 使用）
     */
    public String getApiDoc(String filter) {
        return toolRegistry.executeTool("api_doc", Map.of("filter", filter));
    }
}
