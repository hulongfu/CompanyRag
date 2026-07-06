package com.company.rag.agent.service;

import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.agent.tool.ApiDocTool;
import com.company.rag.agent.tool.CodeSearchTool;
import com.company.rag.agent.tool.DatabaseQueryTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.dashscope.DashscopeChatModel;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * RAG Agent服务
 * 基于Spring AI实现智能工具调用编排
 * 
 * Agent模式工作流程：
 * 1. 用户提问 → LLM分析意图
 * 2. LLM决定是否需要调用工具
 * 3. 如果需要：选择工具 → 执行 → 将结果反馈给LLM
 * 4. LLM基于工具结果生成最终回答
 * 5. 流式返回给用户
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagAgentService {

    private final AgentToolRegistry toolRegistry;
    private final DashscopeChatModel chatModel;

    private static final String AGENT_SYSTEM_PROMPT = """
            你是一个智能企业助手，拥有以下工具可以使用：

            %s

            当用户的问题需要使用工具时，请在回答中标注 [USE_TOOL:工具名] 并说明理由。
            当不需要工具时，直接回答用户问题。
            回答要简洁专业。
            """;

    /**
     * 处理Agent请求，自动选择工具
     * @param userMessage 用户消息
     * @param toolContext 上下文信息
     * @return Agent响应
     */
    public String process(String userMessage, String toolContext) {
        // 获取工具列表
        List<Map<String, Object>> tools = toolRegistry.listTools();
        String toolDescriptions = formatToolDescriptions(tools);

        String systemPrompt = String.format(AGENT_SYSTEM_PROMPT, toolDescriptions);

        // 第一步：让LLM分析是否需要工具
        String initialResponse = chatModel.call(
                new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(userMessage)
                ))
        ).getResult().getOutput().getContent();

        // 检查是否需要调用工具
        if (initialResponse.contains("[USE_TOOL:")) {
            String toolName = extractToolName(initialResponse);
            if (toolName == null) {
                log.warn("Agent检测到工具标记但解析失败，返回原始回答");
                return initialResponse;
            }
            log.info("Agent决定调用工具: {}", toolName);

            try {
                // 执行工具
                String toolResult = toolRegistry.executeTool(toolName, Map.of("query", userMessage));

                // 第二步：LLM基于工具结果生成最终回答
                String enhancedQuery = String.format(
                        "用户问题: %s\n\n工具[%s]返回结果:\n%s\n\n请基于以上工具结果回答用户问题。",
                        userMessage, toolName, toolResult
                );

                return chatModel.call(
                        new Prompt(List.of(
                                new SystemMessage(systemPrompt),
                                new UserMessage(enhancedQuery)
                        ))
                ).getResult().getOutput().getContent();

            } catch (Exception e) {
                log.error("Agent工具执行失败: {}", e.getMessage());
                return "工具执行失败: " + e.getMessage();
            }
        } else {
            // 不需要工具，直接返回LLM的回答
            return initialResponse;
        }
    }

    /**
     * 直接调用数据库查询工具
     */
    public String queryDatabase(String sql) {
        return toolRegistry.executeTool("database_query", Map.of("sql", sql));
    }

    /**
     * 直接调用代码搜索工具
     */
    public String searchCode(String keyword, String ext) {
        return toolRegistry.executeTool("code_search", Map.of("keyword", keyword, "ext", ext));
    }

    /**
     * 直接调用API文档工具
     */
    public String getApiDoc(String filter) {
        return toolRegistry.executeTool("api_doc", Map.of("filter", filter));
    }

    private String formatToolDescriptions(List<Map<String, Object>> tools) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> tool : tools) {
            sb.append("- ").append(tool.get("name"))
              .append(": ").append(tool.get("description"))
              .append("\n");
        }
        return sb.toString();
    }

    private String extractToolName(String response) {
        int start = response.indexOf("[USE_TOOL:") + 10;
        int end = response.indexOf("]", start);
        if (start > 9 && end > start) {
            return response.substring(start, end).trim();
        }
        // 解析失败时仍需判断，但返回null让调用方处理
        return null;
    }
}
