package com.company.rag.rag.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * RAG 缓存配置（Caffeine 内存缓存）
 * 用于缓存 RAG 检索结果，相同问题在 TTL 内直接命中
 * TTL=5 分钟，最大 100 条
 */
@Configuration
@EnableCaching
public class RagCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("ragResults");
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(100)
                .recordStats());
        return manager;
    }
}