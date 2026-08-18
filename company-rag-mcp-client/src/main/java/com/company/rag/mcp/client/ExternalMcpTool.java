package com.company.rag.mcp.client;

import com.company.rag.agent.tool.AgentTool;
import com.company.rag.mcp.model.McpToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 外部 MCP 工具适配器
 * 将外部 MCP Server 的工具适配为 AgentTool 接口
 */
@Slf4j
public class ExternalMcpTool implements AgentTool {
    
    private final String clientId;
    private final McpToolDefinition toolDefinition;
    private final McpClientRegistry clientRegistry;
    private final ObjectMapper objectMapper;
    
    public ExternalMcpTool(String clientId, McpToolDefinition toolDefinition, McpClientRegistry clientRegistry) {
        this.clientId = clientId;
        this.toolDefinition = toolDefinition;
        this.clientRegistry = clientRegistry;
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public String getName() {
        // 工具名称格式：clientId_toolName
        return clientId + "_" + toolDefinition.getName();
    }
    
    @Override
    public String getDescription() {
        return toolDefinition.getDescription();
    }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        // 返回工具的输入 Schema
        if (toolDefinition.getInputSchema() != null) {
            // inputSchema 是 Object 类型，需要转换为 Map
            if (toolDefinition.getInputSchema() instanceof Map) {
                return (Map<String, Object>) toolDefinition.getInputSchema();
            }
            // 如果是其他类型，尝试通过 JSON 转换
            try {
                ObjectMapper mapper = new ObjectMapper();
                String json = mapper.writeValueAsString(toolDefinition.getInputSchema());
                return mapper.readValue(json, Map.class);
            } catch (Exception e) {
                log.warn("转换 inputSchema 失败，返回空 schema", e);
            }
        }
        // 默认空 schema
        return Map.of(
            "type", "object",
            "properties", Map.of()
        );
    }
    
    @Override
    public String execute(Map<String, Object> params) {
        try {
            log.info("调用外部 MCP 工具：{} | 参数：{}", getName(), params);
            
            // 通过 McpClientRegistry 调用工具
            Object result = clientRegistry.callTool(clientId, toolDefinition.getName(), params);
            
            // 将结果转换为字符串
            if (result == null) {
                return "null";
            } else if (result instanceof String) {
                return (String) result;
            } else {
                // 复杂对象转为 JSON 字符串
                return objectMapper.writeValueAsString(result);
            }
        } catch (Exception e) {
            log.error("外部 MCP 工具调用失败：{}", getName(), e);
            return "工具调用失败：" + e.getMessage();
        }
    }
}
