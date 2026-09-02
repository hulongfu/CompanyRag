package com.company.rag.rag.service;

import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j 熔断限流配置类
 * 
 * 注意：本类不再手动创建 Registry Bean，而是让 Spring Boot 自动配置生效
 * 配置参数请在 application.yml 的 resilience4j.* 下配置
 * 
 * 原因：
 * 1. 手动创建 CircuitBreakerRegistry/RateLimiterRegistry Bean 会覆盖 Spring Boot 自动配置
 * 2. 导致 application.yml 中的 resilience4j 配置全部失效
 * 3. 硬编码的值无法通过配置文件调整，不利于运维
 */
@Configuration
public class RagCircuitBreakerConfig {

    // 不再手动定义 Bean，使用 Spring Boot 自动配置
    // CircuitBreakerRegistry 和 RateLimiterRegistry 可以通过依赖注入获取
    
}
