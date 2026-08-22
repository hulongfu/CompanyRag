package com.company.rag.mcp.adapter;

import com.company.rag.agent.tool.AgentTool;
import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.model.McpToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 协议适配器
 * 
 * 职责：
 * 1. 将 AgentTool 转换为 MCP 工具定义格式
 * 2. 调用 AgentToolRegistry 执行工具
 * 3. 处理两种工具实现方式（纯@Tool 注解 和 AgentTool 接口）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolAdapter {
    
    private final AgentToolRegistry agentToolRegistry;
    
    /**
     * 获取所有可用工具列表（MCP 格式）
     * 
     * @return MCP 工具定义列表
     */
    public List<McpToolDefinition> listTools() {
        List<Map<String, Object>> toolsMap = agentToolRegistry.listTools();
        
        return toolsMap.stream()
                .map(toolMap -> {
                    McpToolDefinition definition = new McpToolDefinition();
                    definition.setName((String) toolMap.get("name"));
                    definition.setDescription((String) toolMap.get("description"));
                    definition.setInputSchema(toolMap.get("parameters"));
                    return definition;
                })
                .collect(Collectors.toList());
    }
    
    /**
     * 调用指定工具
     * 
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具执行结果
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        log.info("MCP 工具调用：name={}, args={}", toolName, arguments);
        
        try {
            // 通过 AgentToolRegistry 调用工具
            String result = agentToolRegistry.executeTool(toolName, arguments);
            log.info("MCP 工具调用成功：name={}, resultLength={}", toolName, 
                    result != null ? result.length() : 0);
            return result;
            
        } catch (Exception e) {
            log.error("MCP 工具调用失败：name={}, err={}", toolName, e.getMessage(), e);
            throw new RuntimeException("工具调用失败：" + e.getMessage(), e);
        }
    }
}
