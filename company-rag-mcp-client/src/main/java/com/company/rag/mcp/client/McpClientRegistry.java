package com.company.rag.mcp.client;

import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.model.McpToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client 注册中心
 * 管理多个外部 MCP Server 连接，提供统一的工具调用接口
 */
@Slf4j
@Component
public class McpClientRegistry {
    
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<McpToolDefinition>> toolCache = new ConcurrentHashMap<>();
    private final AgentToolRegistry agentToolRegistry;
    
    /**
     * 构造函数注入 AgentToolRegistry
     */
    public McpClientRegistry(AgentToolRegistry agentToolRegistry) {
        this.agentToolRegistry = agentToolRegistry;
    }
    
    /**
     * 注册 MCP Client
     * @param clientId 客户端 ID
     * @param client MCP 客户端实例
     */
    public void registerClient(String clientId, McpClient client) {
        clients.put(clientId, client);
        log.info("注册 MCP Client: {}", clientId);
        
        // 连接并缓存工具列表
        try {
            client.connect();
            List<McpToolDefinition> tools = client.listTools();
            toolCache.put(clientId, tools);
            log.info("MCP Client [{}] 加载了 {} 个工具", clientId, tools.size());
            
            // 自动注册所有工具到 AgentToolRegistry
            registerToolsToAgent(clientId, tools);
        } catch (Exception e) {
            log.error("MCP Client [{}] 初始化失败", clientId, e);
        }
    }
    
    /**
     * 将 MCP 工具注册到 AgentToolRegistry
     */
    private void registerToolsToAgent(String clientId, List<McpToolDefinition> tools) {
        if (tools == null || agentToolRegistry == null) {
            return;
        }
        
        for (McpToolDefinition tool : tools) {
            try {
                ExternalMcpTool externalTool = new ExternalMcpTool(clientId, tool, this);
                agentToolRegistry.register(externalTool);
                log.info("注册外部 MCP 工具到 Agent: {}", externalTool.getName());
            } catch (Exception e) {
                log.error("注册外部工具失败：{}", clientId + "_" + tool.getName(), e);
            }
        }
    }
    
    /**
     * 获取所有已注册的 Client
     * @return Client Map 的只读视图
     */
    public Map<String, McpClient> getClients() {
        return new ConcurrentHashMap<>(clients);
    }
    
    /**
     * 获取 Client
     * @param clientId 客户端 ID
     * @return MCP 客户端实例
     */
    public McpClient getClient(String clientId) {
        return clients.get(clientId);
    }
    
    /**
     * 列出所有已注册 Client 的工具（合并）
     * @return 所有工具定义列表
     */
    public List<Map<String, Object>> listAllTools() {
        List<Map<String, Object>> allTools = new ArrayList<>();
        
        for (Map.Entry<String, List<McpToolDefinition>> entry : toolCache.entrySet()) {
            String clientId = entry.getKey();
            List<McpToolDefinition> tools = entry.getValue();
            
            if (tools != null) {
                for (McpToolDefinition tool : tools) {
                    Map<String, Object> toolMap = convertToMap(tool, clientId);
                    allTools.add(toolMap);
                }
            }
        }
        
        log.info("返回所有 MCP 工具，共 {} 个", allTools.size());
        return allTools;
    }
    
    /**
     * 调用指定 Client 的工具
     * @param clientId 客户端 ID
     * @param toolName 工具名称
     * @param params 工具参数
     * @return 工具执行结果
     */
    public Object callTool(String clientId, String toolName, Map<String, Object> params) {
        McpClient client = clients.get(clientId);
        if (client == null) {
            throw new IllegalArgumentException("未找到 MCP Client: " + clientId);
        }
        
        log.info("MCP Client [{}] 调用工具：{}, 参数：{}", clientId, toolName, params);
        Object result = client.callTool(toolName, params);
        log.info("MCP Client [{}] 工具 {} 调用完成", clientId, toolName);
        
        return result;
    }
    
    /**
     * 断开所有连接
     */
    public void disconnectAll() {
        for (McpClient client : clients.values()) {
            try {
                client.disconnect();
            } catch (Exception e) {
                log.error("断开 MCP Client 连接失败", e);
            }
        }
        log.info("已断开所有 MCP Client 连接");
    }
    
    /**
     * 将工具定义转换为 Map
     */
    private Map<String, Object> convertToMap(McpToolDefinition tool, String clientId) {
        // 这里简化处理，实际应该使用完整的转换逻辑
        return Map.of(
            "name", tool.getName(),
            "description", tool.getDescription(),
            "inputSchema", tool.getInputSchema(),
            "clientId", clientId
        );
    }
}
