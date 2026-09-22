package com.company.rag.mcp.client;

import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.model.McpToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP Client 注册中心
 * 管理多个外部 MCP Server 连接，提供统一的工具调用接口
 */
@Slf4j
@Component
public class McpClientRegistry {
    
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, List<McpToolDefinition>> toolCache = new ConcurrentHashMap<>();
    /** clientId -> 该源已注册进 Agent 的工具名集合（按来源归属追踪） */
    private final Map<String, Set<String>> agentToolNamesByClient = new ConcurrentHashMap<>();
    /** 进入失败态的 clientId 集合（供调度器重连） */
    private final Set<String> failedClients = ConcurrentHashMap.newKeySet();
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
            failedClients.remove(clientId); // 连接成功，移出失败清单
        } catch (Exception e) {
            failedClients.add(clientId); // 登记失败清单供调度器重连
            log.error("MCP Client [{}] 初始化失败，已加入失败清单", clientId, e);
        }
    }
    
    /**
     * 将 MCP 工具注册到 AgentToolRegistry
     */
    private void registerToolsToAgent(String clientId, List<McpToolDefinition> tools) {
        if (tools == null || agentToolRegistry == null) {
            return;
        }
        
        // 记录该源已注册进 Agent 的工具归属，供按源移除/同步使用
        Set<String> owned = agentToolNamesByClient.computeIfAbsent(clientId, k -> ConcurrentHashMap.newKeySet());
        for (McpToolDefinition tool : tools) {
            try {
                ExternalMcpTool externalTool = new ExternalMcpTool(clientId, tool, this);
                agentToolRegistry.register(externalTool);
                owned.add(externalTool.getName());
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
     * 用远端最新工具列表替换该源(clientId)已注册到 Agent 的工具集：
     * 移除已下架的工具、注册新增的工具，并更新归属追踪与工具缓存。
     * 幂等且并发安全（依赖 ConcurrentHashMap 与 AgentToolRegistry.removeAll 幂等）。
     */
    public void syncTools(String clientId) {
        McpClient client = clients.get(clientId);
        if (client == null) {
            log.warn("syncTools 跳过：未找到 MCP Client: {}", clientId);
            return;
        }
        List<McpToolDefinition> remoteList;
        try {
            remoteList = client.listToolsRemote();
        } catch (Exception e) {
            log.error("MCP Client [{}] 远端获取工具列表失败，无法同步：{}", clientId, e.getMessage());
            return;
        }
        // 远端现有名称集合
        Set<String> remoteNames = new HashSet<>();
        for (McpToolDefinition t : remoteList) {
            remoteNames.add(clientId + "_" + t.getName());
        }
        // 移除已不在远端的工具
        Set<String> owned = agentToolNamesByClient.getOrDefault(clientId, Collections.emptySet());
        List<String> toRemove = owned.stream()
                .filter(n -> !remoteNames.contains(n))
                .collect(Collectors.toList());
        int removed = agentToolRegistry.removeAll(toRemove);
        // 注册远端新增的工具并重新建立归属
        Set<String> newOwned = ConcurrentHashMap.newKeySet();
        for (McpToolDefinition t : remoteList) {
            String name = clientId + "_" + t.getName();
            if (!owned.contains(name)) {
                try {
                    agentToolRegistry.register(new ExternalMcpTool(clientId, t, this));
                    newOwned.add(name);
                } catch (Exception e) {
                    log.error("syncTools 注册新增工具失败：{}", name, e);
                }
            } else {
                // 已在名下的工具保持归属
                newOwned.add(name);
            }
        }
        agentToolNamesByClient.put(clientId, newOwned);
        toolCache.put(clientId, remoteList);
        log.info("MCP Client [{}] 同步完成：移除 {} 个，活跃工具 {} 个", clientId, removed, remoteNames.size());
    }

    /**
     * 移除指定 MCP 来源的全部已注册工具，并释放连接、登记失败清单。
     * 用于探活失败 / 调用失败且远端不可达时按源整机移除。
     */
    public void removeToolsFor(String clientId) {
        Set<String> owned = agentToolNamesByClient.remove(clientId);
        if (owned != null && !owned.isEmpty()) {
            int removed = agentToolRegistry.removeAll(owned);
            log.info("MCP Client [{}] 已移除 {} 个工具", clientId, removed);
        }
        toolCache.remove(clientId);
        McpClient client = clients.remove(clientId);
        if (client != null) {
            try {
                client.disconnect();
            } catch (Exception e) {
                log.warn("MCP Client [{}] 断开失败：{}", clientId, e.getMessage());
            }
        }
        failedClients.add(clientId); // 移除后进入失败清单，等待重连
        log.info("MCP Client [{}] 已按源移除全部工具并标记失败", clientId);
    }

    /**
     * 采集各 MCP 端点当前状态快照（内存态），供管理员状态查询。
     */
    public List<McpEndpointStatus> statusSnapshots() {
        return clients.keySet().stream().map(clientId -> {
            McpClient client = clients.get(clientId);
            Set<String> owned = agentToolNamesByClient.getOrDefault(clientId, Collections.emptySet());
            boolean connected = client != null && client.isConnected();
            return McpEndpointStatus.builder()
                    .clientId(clientId)
                    .connected(connected)
                    .toolCount(owned.size())
                    .registeredToolNames(List.copyOf(owned))
                    .build();
        }).collect(Collectors.toList());
    }

    /** 当前失败清单（只读快照），供调度器重连 */
    public Set<String> getFailedClients() {
        return Set.copyOf(failedClients);
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
