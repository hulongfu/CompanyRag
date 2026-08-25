package com.company.rag.mcp.client;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Client 自动配置
 * 根据配置文件自动创建并注册 MCP Clients
 * 
 * 修复说明：
 * 1. 使用 @PostConstruct 在配置类自身初始化时立即初始化 MCP Clients
 * 2. 模块依赖关系：company-rag-mcp-client 不能被 company-rag-rag 依赖，
 *    所以不能在 @AutoConfigureBefore 中引用 AgentToolConfig
 * 
 * 注意：McpClientProperties 已有 @Component 注解，不需要 @EnableConfigurationProperties
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpClientAutoConfig {
    
    private final McpClientProperties properties;
    private final McpClientRegistry registry;
    
    /**
     * 在配置类实例化后立即初始化所有 MCP Clients
     * 这确保在 Spring 容器完全启动前完成 MCP Client 初始化
     */
    @PostConstruct
    public void init() {
        log.info("开始初始化 MCP Clients...");
        log.info("【DEBUG】配置的 MCP Clients 数量：{}", properties.getClients().size());
        for (McpClientProperties.ClientConfig config : properties.getClients()) {
            log.info("【DEBUG】发现 MCP Client 配置：id={}, enabled={}, url={}", 
                config.getId(), config.isEnabled(), config.getUrl());
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
