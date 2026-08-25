package com.company.rag.mcp.handler;

import com.company.rag.mcp.model.JsonRpcRequest;
import com.company.rag.mcp.model.JsonRpcResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonRpcHandler 单元测试
 */
class JsonRpcHandlerTest {
    
    private JsonRpcHandler handler;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new JsonRpcHandler(objectMapper);
    }
    
    @Test
    @DisplayName("解析合法的 JSON-RPC 请求")
    void parseValidRequest() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/list",
                "id": 1
            }
            """;
        
        JsonRpcRequest request = handler.parseRequest(requestBody);
        
        assertEquals("2.0", request.getJsonrpc());
        assertEquals("tools/list", request.getMethod());
        assertEquals(1, request.getId());
    }
    
    @Test
    @DisplayName("解析带参数的 JSON-RPC 请求")
    void parseRequestWithParams() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/call",
                "params": {
                    "name": "database_query",
                    "arguments": {
                        "sql": "SELECT * FROM users"
                    }
                },
                "id": 2
            }
            """;
        
        JsonRpcRequest request = handler.parseRequest(requestBody);
        
        assertEquals("tools/call", request.getMethod());
        // assertEquals("database_query", request.getParams().getName());
        assertNotNull(request.getParams());
    }
    
    @Test
    @DisplayName("解析不支持的 JSON-RPC 版本")
    void parseUnsupportedVersion() {
        String requestBody = """
            {
                "jsonrpc": "1.0",
                "method": "tools/list",
                "id": 1
            }
            """;
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> handler.parseRequest(requestBody)
        );
        
        assertTrue(exception.getMessage().contains("不支持的 JSON-RPC 版本"));
    }
    
    @Test
    @DisplayName("解析空 method 的请求")
    void parseEmptyMethod() {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "",
                "id": 1
            }
            """;
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> handler.parseRequest(requestBody)
        );
        
        assertTrue(exception.getMessage().contains("method 不能为空"));
    }
    
    @Test
    @DisplayName("构建成功响应")
    void buildSuccessResponse() {
        Object result = "{\"tools\": []}";
        JsonRpcResponse response = handler.buildSuccessResponse(1, result);
        
        assertEquals("2.0", response.getJsonrpc());
        assertEquals(1, response.getId());
        assertNotNull(response.getResult());
        assertNull(response.getError());
    }
    
    @Test
    @DisplayName("构建错误响应")
    void buildErrorResponse() {
        JsonRpcResponse response = handler.buildErrorResponse(1, -32601, "Method not found");
        
        assertEquals("2.0", response.getJsonrpc());
        assertEquals(1, response.getId());
        assertNull(response.getResult());
        assertNotNull(response.getError());
        assertEquals(-32601, response.getError().getCode());
        assertEquals("Method not found", response.getError().getMessage());
    }
    
    @Test
    @DisplayName("序列化响应为 JSON")
    void serializeResponse() throws Exception {
        JsonRpcResponse response = handler.buildSuccessResponse(1, "{\"result\": \"ok\"}");
        String json = handler.serializeResponse(response);
        
        assertNotNull(json);
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":1"));
        assertTrue(json.contains("\"result\""));
    }
}
