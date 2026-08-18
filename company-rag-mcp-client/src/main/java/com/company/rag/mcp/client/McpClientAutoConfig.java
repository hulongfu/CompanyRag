package com.company.rag.mcp.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * MCP Client 自动配置
 * 根据配置文件自动创建并注册 MCP Clients
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(McpClientProperties.class)
@ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpClientAutoConfig {
    
    private final McpClientProperties properties;
    private final McpClientRegistry registry;
    
    /**
     * 初始化所有配置的 MCP Clients
     */
    @Bean
    public McpClientInitializer mcpClientInitializer() {
        return new McpClientInitializer(properties, registry);
    }
    
    /**
     * MCP Client 初始化器
     */
    @RequiredArgsConstructor
    public static class McpClientInitializer {
        
        private final McpClientProperties properties;
        private final McpClientRegistry registry;
        
        /**
         * 在容器启动后初始化所有客户端
         */
        @org.springframework.context.event.EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
        public void onApplicationEvent(org.springframework.context.event.ContextRefreshedEvent event) {
            log.info("开始初始化 MCP Clients...");
            
            for (McpClientProperties.ClientConfig config : properties.getClients()) {
                if (!config.isEnabled()) {
                    log.info("跳过禁用的 MCP Client: {}", config.getId());
                    continue;
                }
                
                try {
                    HttpMcpClient client = new HttpMcpClient(
                        config.getId(),
                        config.getUrl(),
                        config.getTimeout(),
                        config.getHeaders()
                    );
                    
                    registry.registerClient(config.getId(), client);
                    log.info("MCP Client [{}] 初始化成功，URL: {}", config.getId(), config.getUrl());
                } catch (Exception e) {
                    log.error("MCP Client [{}] 初始化失败", config.getId(), e);
                }
            }
            
            log.info("MCP Clients 初始化完成");
        }
        
        /**
         * 应用关闭时断开所有连接
         */
        @PreDestroy
        public void destroy() {
            log.info("正在关闭所有 MCP Clients...");
            registry.disconnectAll();
        }
    }
}
