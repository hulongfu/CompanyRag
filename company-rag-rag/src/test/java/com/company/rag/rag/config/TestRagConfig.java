package com.company.rag.rag.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * 集成测试配置类
 * 用于在 company-rag-rag 模块中启动 Spring Boot 测试上下文
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.company.rag")
public class TestRagConfig {
    
    /**
     * 提供 MeterRegistry bean 用于指标记录
     * 使用 SimpleMeterRegistry 作为测试环境的最小实现
     */
    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
