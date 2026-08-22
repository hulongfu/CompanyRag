package com.company.rag.mcp.controller;

import com.company.rag.mcp.adapter.McpToolAdapter;
import com.company.rag.mcp.handler.JsonRpcHandler;
import com.company.rag.mcp.model.JsonRpcRequest;
import com.company.rag.mcp.model.JsonRpcResponse;
import com.company.rag.mcp.model.McpToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP Server HTTP 端点
 * 
 * 支持的 MCP 方法：
 * - GET /mcp/tools: 列出所有可用工具（对应 MCP 的 tools/list 方法）
 * - POST /mcp: 统一的 JSON-RPC 端点（支持 tools/list 和 tools/call）
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpController {
    
    private final JsonRpcHandler jsonRpcHandler;
    private final McpToolAdapter toolAdapter;
    private final ObjectMapper objectMapper;
    
    /**
     * MCP 统一端点（POST）
     * 
     * 支持的方法：
     * - tools/list: 列出所有可用工具
     * - tools/call: 调用指定工具
     * 
     * @param requestBody JSON-RPC 请求体
     * @return JSON-RPC 响应
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handleMcpRequest(@RequestBody String requestBody) {
        log.info("MCP 请求：{}", requestBody);
        
        try {
            // 解析 JSON-RPC 请求
            JsonRpcRequest request = jsonRpcHandler.parseRequest(requestBody);
            
            // 根据 method 路由到不同处理逻辑
            JsonRpcResponse response;
            switch (request.getMethod()) {
                case "tools/list":
                    response = handleToolsList(request.getId());
                    break;
                    
                case "tools/call":
                    response = handleToolsCall(request);
                    break;
                    
                default:
                    response = jsonRpcHandler.buildErrorResponse(
                            request.getId(),
                            JsonRpcHandler.METHOD_NOT_FOUND,
                            "不支持的方法：" + request.getMethod()
                    );
            }
            
            // 返回 JSON-RPC 响应
            String responseBody = jsonRpcHandler.serializeResponse(response);
            log.info("MCP 响应：{}", responseBody);
            return ResponseEntity.ok(responseBody);
            
        } catch (IllegalArgumentException e) {
            // 请求格式错误
            log.warn("MCP 请求格式错误：{}", e.getMessage());
            String errorResponse = jsonRpcHandler.serializeResponse(
                    jsonRpcHandler.buildErrorResponse(
                            extractRequestId(requestBody),
                            JsonRpcHandler.INVALID_REQUEST,
                            e.getMessage()
                    )
            );
            return ResponseEntity.badRequest().body(errorResponse);
            
        } catch (Exception e) {
            // 内部错误
            log.error("MCP 请求处理失败：{}", e.getMessage(), e);
            String errorResponse = jsonRpcHandler.serializeResponse(
                    jsonRpcHandler.buildErrorResponse(
                            extractRequestId(requestBody),
                            JsonRpcHandler.INTERNAL_ERROR,
                            "内部错误：" + e.getMessage()
                    )
            );
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
    
    /**
     * 列出所有可用工具（GET 方式，方便浏览器直接访问）
     * 
     * @return 工具列表
     */
    @GetMapping(value = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<McpToolDefinition>> listTools() {
        log.info("MCP 工具列表请求");
        
        List<McpToolDefinition> tools = toolAdapter.listTools();
        log.info("MCP 工具列表：{} 个工具", tools.size());
        
        return ResponseEntity.ok(tools);
    }
    
    /**
     * 处理 tools/list 方法
     */
    private JsonRpcResponse handleToolsList(Object requestId) {
        log.info("处理 tools/list 请求");
        
        List<McpToolDefinition> tools = toolAdapter.listTools();
        
        // MCP tools/list 返回格式
        Map<String, Object> result = Map.of("tools", tools);
        
        return jsonRpcHandler.buildSuccessResponse(requestId, result);
    }
    
    /**
     * 处理 tools/call 方法
     */
    private JsonRpcResponse handleToolsCall(JsonRpcRequest request) {
        // params 可能是 JsonRpcParams 或 Map（initialize 请求）
        if (!(request.getParams() instanceof JsonRpcRequest.JsonRpcParams)) {
            return jsonRpcHandler.buildErrorResponse(request.getId(), -32602, "Invalid request parameters");
        }
        
        JsonRpcRequest.JsonRpcParams params = (JsonRpcRequest.JsonRpcParams) request.getParams();
        String toolName = params.getName();
        Map<String, Object> arguments = null;
        
        if (params.getArguments() instanceof Map) {
            arguments = (Map<String, Object>) params.getArguments();
        }
        
        log.info("处理 tools/call 请求：tool={}", toolName);
        
        try {
            String result = toolAdapter.callTool(toolName, arguments);
            
            // MCP tools/call 返回格式
            Map<String, Object> responseContent = Map.of(
                    "content", List.of(
                            Map.of("type", "text", "text", result)
                    )
            );
            
            return jsonRpcHandler.buildSuccessResponse(request.getId(), responseContent);
            
        } catch (Exception e) {
            return jsonRpcHandler.buildErrorResponse(
                    request.getId(),
                    JsonRpcHandler.INTERNAL_ERROR,
                    e.getMessage()
            );
        }
    }
    
    /**
     * 从请求体中提取 requestId（用于错误响应）
     */
    private Object extractRequestId(String requestBody) {
        try {
            JsonRpcRequest request = objectMapper.readValue(requestBody, JsonRpcRequest.class);
            return request.getId();
        } catch (Exception e) {
            return null;
        }
    }
}
