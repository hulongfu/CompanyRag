package com.company.rag.rag.config;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * 集成测试配置类
 * 用于在 company-rag-rag 模块中启动 Spring Boot 测试上下文
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = "com.company.rag")
public class TestRagConfig {
    // 空的配置类，仅用于启动 Spring Boot 测试上下文
}
