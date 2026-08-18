package com.company.rag.mcp.client;

import com.company.rag.mcp.model.McpToolDefinition;

import java.util.List;
import java.util.Map;

/**
 * MCP 协议客户端接口
 * 负责与外部 MCP Server 通信（HTTP + JSON-RPC 2.0）
 */
public interface McpClient {
    
    /**
     * 获取客户端 ID（对应一个 MCP Server 连接）
     * @return 客户端唯一标识
     */
    String getClientId();
    
    /**
     * 连接 MCP Server（初始化）
     */
    void connect();
    
    /**
     * 断开连接
     */
    void disconnect();
    
    /**
     * 检查连接状态
     * @return true 表示已连接
     */
    boolean isConnected();
    
    /**
     * 获取工具列表
     * @return MCP 工具定义列表
     */
    List<McpToolDefinition> listTools();
    
    /**
     * 调用工具
     * @param toolName 工具名称
     * @param params 工具参数
     * @return 工具执行结果
     */
    Object callTool(String toolName, Map<String, Object> params);
}
