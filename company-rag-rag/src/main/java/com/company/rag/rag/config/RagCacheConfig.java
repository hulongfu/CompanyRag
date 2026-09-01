package com.company.rag.rag.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * RAG 缓存配置（Caffeine 内存缓存）
 * 用于缓存 RAG 检索结果，相同问题在 TTL 内直接命中
 * TTL=5 分钟，最大 100 条
 * 
 * 同时配置下载清理标识缓存（downloadCleanup）：
 * - 用于标识已执行过清理，避免重复清理
 * - TTL=24 小时，最大 1000 条
 * - 使用固定 key（downloadCleanFlag），利用缓存过期机制实现每天清理一次
 */
@Configuration
@EnableCaching
public class RagCacheConfig {

    /**
     * 主缓存管理器 - 用于 RAG 检索结果
     * TTL=5 分钟，最大 100 条
     */
    @Primary  // 标记为默认的 CacheManager
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("ragResults");
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(100)
                .recordStats());
        return manager;
    }
    
    /**
     * 下载清理标识缓存
     * TTL=24 小时，最大 1000 条
     * 注意：缓存名称必须与 @Cacheable 的 value 对应
     * 使用固定 key（downloadCleanFlag），缓存过期后可再次执行清理
     */
    @Bean
    public CacheManager downloadCleanupCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("downloadCleanup");
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(24, TimeUnit.HOURS)
                .maximumSize(1000)
                .recordStats());
        return manager;
    }
}