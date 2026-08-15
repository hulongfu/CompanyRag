package com.company.rag.rag.service;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Resilience4j 熔断限流配置
 * 保护 LLM 调用和后端服务不被突发流量打垮
 */
@Configuration
public class RagCircuitBreakerConfig {

    /**
     * LLM 调用熔断器配置
     * - 失败率超过 50% 则熔断
     * - 熔断后 30 秒尝试半开
     * - 最小调用数 10 次才触发熔断判断
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    /**
     * 速率限制器配置
     * - 每租户每秒最多 5 次 LLM 调用
     * - 突发容量 10 次
     */
    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(5)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMillis(500))
                .build();
        return RateLimiterRegistry.of(config);
    }
}
