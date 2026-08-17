package com.company.rag.mcp.handler;

import com.company.rag.mcp.model.JsonRpcRequest;
import com.company.rag.mcp.model.JsonRpcResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JSON-RPC 协议处理器
 * 
 * 职责：
 * 1. 解析 HTTP 请求体为 JsonRpcRequest
 * 2. 验证协议格式（jsonrpc 版本、method 合法性）
 * 3. 构建 JsonRpcResponse 响应
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonRpcHandler {
    
    private final ObjectMapper objectMapper;
    
    /**
     * 解析 HTTP 请求体为 JSON-RPC 请求
     */
    public JsonRpcRequest parseRequest(String requestBody) {
        try {
            JsonRpcRequest request = objectMapper.readValue(requestBody, JsonRpcRequest.class);
            
            // 验证协议版本
            if (!"2.0".equals(request.getJsonrpc())) {
                throw new IllegalArgumentException("不支持的 JSON-RPC 版本：" + request.getJsonrpc());
            }
            
            // 验证 method
            if (request.getMethod() == null || request.getMethod().isBlank()) {
                throw new IllegalArgumentException("method 不能为空");
            }
            
            return request;
            
        } catch (Exception e) {
            log.error("JSON-RPC 请求解析失败：{}", e.getMessage());
            throw new IllegalArgumentException("JSON-RPC 请求格式错误：" + e.getMessage(), e);
        }
    }
    
    /**
     * 构建成功响应
     */
    public JsonRpcResponse buildSuccessResponse(Object requestId, Object result) {
        return JsonRpcResponse.success(requestId, result);
    }
    
    /**
     * 构建错误响应
     */
    public JsonRpcResponse buildErrorResponse(Object requestId, int errorCode, String errorMessage) {
        return JsonRpcResponse.error(requestId, errorCode, errorMessage, null);
    }
    
    /**
     * 构建错误响应（带附加数据）
     */
    public JsonRpcResponse buildErrorResponse(Object requestId, int errorCode, String errorMessage, Object errorData) {
        return JsonRpcResponse.error(requestId, errorCode, errorMessage, errorData);
    }
    
    /**
     * 将响应对象序列化为 JSON 字符串
     */
    public String serializeResponse(JsonRpcResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("JSON-RPC 响应序列化失败：{}", e.getMessage());
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"响应序列化失败\"}}";
        }
    }
    
    // JSON-RPC 标准错误码
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
}
