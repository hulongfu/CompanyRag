package com.company.rag.mcp.client;

import com.company.rag.agent.tool.AgentTool;
import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.model.McpToolDefinition;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HttpMcpClient 集成测试
 * 使用 WireMock 模拟外部 MCP Server
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HttpMcpClientIntegrationTest {
    
    private WireMockServer wireMockServer;
    private HttpMcpClient mcpClient;
    private McpClientRegistry clientRegistry;
    private AgentToolRegistry agentToolRegistry;
    
    @BeforeAll
    void setUpAll() {
        // 启动 WireMock 服务器
        wireMockServer = new WireMockServer(8089);
        wireMockServer.start();
        
        // 创建 AgentToolRegistry
        agentToolRegistry = new AgentToolRegistry(List.of());
        
        // 创建 McpClientRegistry
        clientRegistry = new McpClientRegistry(agentToolRegistry);
        
        // 创建 MCP Client
        mcpClient = new HttpMcpClient(
            "test-client",
            "http://localhost:8089/mcp",
            30000,
            Map.of("Authorization", "Bearer test-token")
        );
    }
    
    @AfterAll
    void tearDownAll() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }
    
    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
    }
    
    @Test
    @DisplayName("测试连接和断开连接")
    void testConnectAndDisconnect() {
        // 测试连接
        mcpClient.connect();
        assertTrue(mcpClient.isConnected(), "应该已连接");
        
        // 测试断开
        mcpClient.disconnect();
        assertFalse(mcpClient.isConnected(), "应该已断开");
    }
    
    @Test
    @DisplayName("测试获取工具列表")
    void testListTools() {
        // 模拟 tools/list 响应
        String mockResponse = """
            {
              "jsonrpc": "2.0",
              "id": "req-123",
              "result": [
                {
                  "name": "read_file",
                  "description": "读取文件内容",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "path": {
                        "type": "string",
                        "description": "文件路径"
                      }
                    },
                    "required": ["path"]
                  }
                },
                {
                  "name": "write_file",
                  "description": "写入文件内容",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "path": {
                        "type": "string",
                        "description": "文件路径"
                      },
                      "content": {
                        "type": "string",
                        "description": "文件内容"
                      }
                    },
                    "required": ["path", "content"]
                  }
                }
              ]
            }
            """;
        
        stubFor(post(urlEqualTo("/mcp"))
                .withHeader("Content-Type", equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(containing("\"method\":\"tools/list\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(mockResponse)));
        
        // 调用 listTools
        List<McpToolDefinition> tools = mcpClient.listTools();
        
        // 验证结果
        assertNotNull(tools);
        assertEquals(2, tools.size());
        assertEquals("read_file", tools.get(0).getName());
        assertEquals("读取文件内容", tools.get(0).getDescription());
        
        // 验证请求
        verify(postRequestedFor(urlEqualTo("/mcp"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
    }
    
    @Test
    @DisplayName("测试调用工具")
    void testCallTool() {
        // 模拟 tools/call 响应
        String mockResponse = """
            {
              "jsonrpc": "2.0",
              "id": "req-456",
              "result": {
                "success": true,
                "content": "文件内容测试"
              }
            }
            """;
        
        stubFor(post(urlEqualTo("/mcp"))
                .withHeader("Content-Type", equalTo(MediaType.APPLICATION_JSON_VALUE))
                .withRequestBody(containing("\"method\":\"tools/call\""))
                .withRequestBody(containing("\"name\":\"read_file\""))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(mockResponse)));
        
        // 调用工具
        Map<String, Object> params = Map.of("path", "/test/file.txt");
        Object result = mcpClient.callTool("read_file", params);
        
        // 验证结果
        assertNotNull(result);
        assertTrue(result instanceof Map);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> resultMap = (Map<String, Object>) result;
        assertTrue((Boolean) resultMap.get("success"));
        assertEquals("文件内容测试", resultMap.get("content"));
    }
    
    @Test
    @DisplayName("测试工具调用错误处理")
    void testCallToolError() {
        // 模拟错误响应
        String mockResponse = """
            {
              "jsonrpc": "2.0",
              "id": "req-789",
              "error": {
                "code": -32000,
                "message": "文件不存在"
              }
            }
            """;
        
        stubFor(post(urlEqualTo("/mcp"))
                .withHeader("Content-Type", equalTo(MediaType.APPLICATION_JSON_VALUE))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(mockResponse)));
        
        // 调用工具应该抛出异常
        assertThrows(RuntimeException.class, () -> {
            mcpClient.callTool("read_file", Map.of("path", "/nonexistent.txt"));
        });
    }
    
    @Test
    @DisplayName("测试注册到 AgentToolRegistry")
    void testRegisterToAgentToolRegistry() {
        // 模拟 tools/list 响应
        String mockResponse = """
            {
              "jsonrpc": "2.0",
              "id": "req-999",
              "result": [
                {
                  "name": "read_file",
                  "description": "读取文件内容",
                  "inputSchema": {
                    "type": "object",
                    "properties": {
                      "path": {"type": "string"}
                    },
                    "required": ["path"]
                  }
                }
              ]
            }
            """;
        
        stubFor(post(urlEqualTo("/mcp"))
                .withHeader("Content-Type", equalTo(MediaType.APPLICATION_JSON_VALUE))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(mockResponse)));
        
        // 注册 Client
        clientRegistry.registerClient("test-client", mcpClient);
        
        // 验证工具已注册到 AgentToolRegistry
        AgentTool tool = agentToolRegistry.getTool("test-client_read_file");
        assertNotNull(tool);
        assertEquals("test-client_read_file", tool.getName());
        assertEquals("读取文件内容", tool.getDescription());
        
        // 验证参数 schema
        Map<String, Object> schema = tool.getParameterSchema();
        assertNotNull(schema);
        assertEquals("object", schema.get("type"));
    }
}
