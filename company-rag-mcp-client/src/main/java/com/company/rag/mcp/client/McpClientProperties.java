package com.company.rag.mcp.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP Client 配置属性
 * 支持配置多个 MCP Server 连接
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp.clients")
public class McpClientProperties {
    
    /**
     * 客户端配置列表
     */
    private List<ClientConfig> clients = new ArrayList<>();
    
    /**
     * 单个客户端配置
     */
    @Data
    public static class ClientConfig {
        
        /**
         * 客户端唯一标识
         */
        private String id;
        
        /**
         * 客户端名称（描述性）
         */
        private String name;
        
        /**
         * MCP Server URL
         */
        private String url;
        
        /**
         * 是否启用
         */
        private boolean enabled = true;
        
        /**
         * 连接超时（毫秒）
         */
        private int timeout = 30000;
        
        /**
         * 自定义请求头
         */
        private Map<String, String> headers;
    }
}
