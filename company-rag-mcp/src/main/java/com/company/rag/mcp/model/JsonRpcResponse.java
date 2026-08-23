package com.company.rag.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * JSON-RPC 2.0 响应格式
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JsonRpcResponse {
    
    /**
     * JSON-RPC 协议版本，必须为 "2.0"
     */
    private String jsonrpc = "2.0";
    
    /**
     * 响应 ID，与请求 ID 对应
     */
    private Object id;
    
    /**
     * 响应结果（成功时）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object result;
    
    /**
     * 错误信息（失败时）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonRpcError error;
    
    /**
     * 创建成功响应
     */
    public static JsonRpcResponse success(Object id, Object result) {
        return new JsonRpcResponse("2.0", id, result, null);
    }
    
    /**
     * 创建错误响应
     */
    public static JsonRpcResponse error(Object id, int code, String message, Object data) {
        return new JsonRpcResponse("2.0", id, null, new JsonRpcError(code, message, data));
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JsonRpcError {
        /**
         * 错误码
         */
        private int code;
        
        /**
         * 错误消息
         */
        private String message;
        
        /**
         * 附加数据（可选）
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Object data;
    }
}
