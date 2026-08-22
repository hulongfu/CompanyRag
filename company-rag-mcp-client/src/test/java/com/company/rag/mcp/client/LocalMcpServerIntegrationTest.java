package com.company.rag.mcp.client;

import com.company.rag.agent.tool.AgentTool;
import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.model.McpToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地 MCP 服务器集成测试
 * 测试连接：http://127.0.0.1:9001/mcp（文件读取 MCP Server）
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LocalMcpServerIntegrationTest {

    private HttpMcpClient mcpClient;
    private McpClientRegistry clientRegistry;
    private AgentToolRegistry agentToolRegistry;

    @BeforeAll
    void setUpAll() {
        log.info("开始测试连接本地 MCP 服务器：http://127.0.0.1:9001/mcp");
        
        // 创建 AgentToolRegistry
        agentToolRegistry = new AgentToolRegistry(List.of());
        
        // 创建 McpClientRegistry
        clientRegistry = new McpClientRegistry(agentToolRegistry);
        
        // 创建 MCP Client（连接本地服务器）
        mcpClient = new HttpMcpClient(
            "local-file",
            "http://127.0.0.1:9001/mcp",
            30000,
            Map.of() // 无认证头
        );
    }

    @AfterAll
    void tearDownAll() {
        if (mcpClient != null && mcpClient.isConnected()) {
            mcpClient.disconnect();
        }
    }

    @Test
    @DisplayName("测试连接本地 MCP 服务器")
    void testConnectToLocalMcpServer() {
        log.info("测试连接到本地 MCP 服务器");
        
        // 连接
        mcpClient.connect();
        assertTrue(mcpClient.isConnected(), "应该成功连接到本地 MCP 服务器");
        
        log.info("✓ 成功连接到本地 MCP 服务器");
    }

    @Test
    @DisplayName("测试获取工具列表")
    void testListTools() {
        log.info("测试获取工具列表");
        
        // 确保已连接
        if (!mcpClient.isConnected()) {
            mcpClient.connect();
        }
        
        // 获取工具列表
        List<McpToolDefinition> tools = mcpClient.listTools();
        
        // 验证结果
        assertNotNull(tools, "工具列表不应为 null");
        assertFalse(tools.isEmpty(), "工具列表不应为空");
        
        log.info("✓ 成功获取工具列表，工具数量：{}", tools.size());
        log.info("工具列表：");
        for (McpToolDefinition tool : tools) {
            log.info("  - {}: {}", tool.getName(), tool.getDescription());
        }
        
        // 验证工具名称
        assertTrue(tools.stream().anyMatch(t -> t.getName().contains("read") || t.getName().contains("file")), 
                "应该包含文件读取相关的工具");
    }

    @Test
    @DisplayName("测试注册到 AgentToolRegistry")
    void testRegisterToAgentToolRegistry() {
        log.info("测试注册到 AgentToolRegistry");
        
        // 确保已连接
        if (!mcpClient.isConnected()) {
            mcpClient.connect();
        }
        
        // 注册 Client
        clientRegistry.registerClient("local-file", mcpClient);
        
        // 等待工具注册完成
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 获取所有已注册的工具
        List<Map<String, Object>> allTools = agentToolRegistry.listTools();
        assertNotNull(allTools, "工具列表不应为 null");
        assertFalse(allTools.isEmpty(), "工具列表不应为空");
        
        log.info("✓ 成功注册到 AgentToolRegistry，工具数量：{}", allTools.size());
        log.info("所有工具：");
        for (Map<String, Object> toolMap : allTools) {
            log.info("  - {} (描述：{})", toolMap.get("name"), toolMap.get("description"));
        }
        
        // 验证工具名称前缀
        boolean hasPrefixedTool = allTools.stream()
                .anyMatch(t -> ((String) t.get("name")).startsWith("local-file_"));
        assertTrue(hasPrefixedTool, "工具名称应该以 'local-file_' 为前缀");
    }
}
