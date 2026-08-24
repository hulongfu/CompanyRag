package com.company.rag.rag.config;

import com.company.rag.agent.tool.AgentTool;
import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.client.McpClientRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聚合工具回调提供者
 * 将 AgentToolRegistry 中的所有工具 (包括 MCP 工具) 转换为 Spring AI 的 ToolCallback
 * 
 * 工作原理:
 * 1. 从 AgentToolRegistry 获取所有已注册的工具
 * 2. 将每个 AgentTool 转换为 ToolCallback
 * 3. 提供给 ChatClient 使用
 */
@Slf4j
@Component
public class AggregatedToolCallbackProvider implements ToolCallbackProvider {
    
    private final AgentToolRegistry agentToolRegistry;
    private final McpClientRegistry mcpClientRegistry;
    private final ObjectMapper objectMapper;
    
    public AggregatedToolCallbackProvider(AgentToolRegistry agentToolRegistry,
                                          McpClientRegistry mcpClientRegistry) {
        this.agentToolRegistry = agentToolRegistry;
        this.mcpClientRegistry = mcpClientRegistry;
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public ToolCallback[] getToolCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();
        
        // 1. 添加 AgentToolRegistry 中的所有工具
        List<Map<String, Object>> tools = agentToolRegistry.listTools();
        log.debug("从 AgentToolRegistry 获取工具：{}", tools.size());
        
        for (Map<String, Object> toolInfo : tools) {
            String toolName = (String) toolInfo.get("name");
            String description = (String) toolInfo.get("description");
            
            try {
                AgentTool agentTool = agentToolRegistry.getTool(toolName);
                if (agentTool != null) {
                    ToolCallback callback = createToolCallback(agentTool, toolName, description);
                    callbacks.add(callback);
                    log.debug("添加工具回调：{}", toolName);
                } else {
                    log.warn("工具 {} 在 AgentToolRegistry 中不存在", toolName);
                }
            } catch (Exception e) {
                log.error("创建工具回调失败：{}", toolName, e);
            }
        }
        
        log.info("聚合工具回调提供者：共 {} 个工具", callbacks.size());
        return callbacks.toArray(new ToolCallback[0]);
    }
    
    /**
     * 创建 ToolCallback
     */
    private ToolCallback createToolCallback(AgentTool agentTool, String name, String description) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                // 构建工具定义
                // inputSchema 需要是 JSON 字符串格式
                String inputSchemaJson;
                try {
                    inputSchemaJson = objectMapper.writeValueAsString(agentTool.getParameterSchema());
                } catch (Exception e) {
                    log.warn("转换 inputSchema 失败，使用默认 schema", e);
                    inputSchemaJson = "{\"type\":\"object\",\"properties\":{}}";
                }
                
                return ToolDefinition.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(inputSchemaJson)
                        .build();
            }
            
            @Override
            public String call(String input) {
                try {
                    // Spring AI 传递的是 JSON 字符串参数，需要解析为 Map
                    log.debug("调用工具：{}, input={}", name, input);
                    
                    // 解析 JSON 参数
                    Map<String, Object> params;
                    if (input == null || input.trim().isEmpty() || "null".equals(input)) {
                        params = Map.of();
                    } else {
                        try {
                            params = objectMapper.readValue(input, Map.class);
                        } catch (Exception e) {
                            log.warn("解析输入参数失败，使用空参数：{}", input, e);
                            params = Map.of();
                        }
                    }
                    
                    // 调用 AgentTool
                    String result = agentTool.execute(params);
                    log.debug("工具 {} 调用完成，resultLength={}", name, 
                             result != null ? result.length() : 0);
                    return result;
                } catch (Exception e) {
                    log.error("工具调用失败：{}", name, e);
                    return "工具调用失败：" + e.getMessage();
                }
            }
        };
    }
}
