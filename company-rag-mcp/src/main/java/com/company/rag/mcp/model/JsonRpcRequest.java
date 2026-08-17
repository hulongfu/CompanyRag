package com.company.rag.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * JSON-RPC 2.0 请求格式
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonRpcRequest {
    
    /**
     * JSON-RPC 协议版本，必须为 "2.0"
     */
    private String jsonrpc;
    
    /**
     * 请求方法名，例如："tools/list"、"tools/call"
     */
    private String method;
    
    /**
     * 请求参数
     */
    private JsonRpcParams params;
    
    /**
     * 请求 ID，用于匹配请求和响应
     */
    private Object id;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JsonRpcParams {
        /**
         * 工具名称（tools/call 需要）
         */
        private String name;
        
        /**
         * 工具参数（tools/call 需要）
         */
        private Object arguments;
    }
}
