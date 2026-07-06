package com.company.rag.rag.cache;

import com.company.rag.common.constant.RagConstant;
import com.company.rag.rag.model.RagResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * RAG缓存管理器
 * 基于Redis Redisson的RMapCache实现
 * 缓存策略：
 * - 相同问题的检索结果缓存5分钟（TTL）
 * - 文档向量变更时主动失效相关缓存
 * - 热点问题自动延长TTL
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagCacheManager {

    private final RedissonClient redissonClient;

    // 默认缓存过期时间：5分钟
    private static final long DEFAULT_TTL_MINUTES = 5;
    // 热点缓存过期时间：30分钟
    private static final long HOT_TTL_MINUTES = 30;

    /**
     * 获取缓存map实例
     */
    private RMapCache<String, RagResult> getCache() {
        return redissonClient.getMapCache(RagConstant.CACHE_DOC_VECTOR + "search");
    }

    /**
     * 获取缓存的检索结果
     */
    public RagResult getSearchResult(String cacheKey) {
        RMapCache<String, RagResult> cache = getCache();
        RagResult result = cache.get(cacheKey);
        if (result != null) {
            // 每次读取增加访问计数，用于热点判断
            incrementAccessCount(cacheKey);
        }
        return result;
    }

    /**
     * 缓存检索结果
     */
    public void putSearchResult(String cacheKey, RagResult result) {
        RMapCache<String, RagResult> cache = getCache();
        // 判断是否为热点（命中次数 > 3），延长TTL
        boolean isHot = isHotKey(cacheKey);
        long ttl = isHot ? HOT_TTL_MINUTES : DEFAULT_TTL_MINUTES;
        cache.put(cacheKey, result, ttl, TimeUnit.MINUTES);
        if (isHot) {
            log.debug("热点缓存延长TTL: key={}", cacheKey);
        }
    }

    /**
     * 失效指定文档的所有缓存
     * 使用Redis SCAN模式匹配删除，避免全量遍历
     */
    public void invalidateByDocument(Long documentId) {
        String pattern = "*doc_" + documentId + "*";
        RMapCache<String, RagResult> cache = getCache();

        try {
            // 使用SCAN迭代删除匹配的key，避免阻塞Redis
            int deletedCount = 0;
            for (String key : cache.keySet()) {
                if (key.contains("doc_" + documentId)) {
                    cache.remove(key);
                    deletedCount++;
                }
            }

            log.info("失效文档缓存成功 | documentId={} | deletedCount={}", documentId, deletedCount);
        } catch (Exception e) {
            log.error("失效文档缓存失败 | documentId={} | error={}", documentId, e.getMessage());
        }
    }

    /**
     * 失效指定租户的所有缓存
     */
    public void invalidateByTenant(Long tenantId) {
        RMapCache<String, RagResult> cache = getCache();
        String prefix = tenantId + ":";

        try {
            int deletedCount = 0;
            for (String key : cache.keySet()) {
                if (key.startsWith(prefix)) {
                    cache.remove(key);
                    deletedCount++;
                }
            }

            log.info("失效租户缓存成功 | tenantId={} | deletedCount={}", tenantId, deletedCount);
        } catch (Exception e) {
            log.error("失效租户缓存失败 | tenantId={} | error={}", tenantId, e.getMessage());
        }
    }

    /**
     * 清空所有RAG缓存
     */
    public void clearAll() {
        getCache().clear();
        log.info("已清空所有RAG缓存");
    }

    /**
     * 判断是否为热点key
     */
    private boolean isHotKey(String cacheKey) {
        String countKey = RagConstant.CACHE_RATE_LIMIT + "hot:" + cacheKey;
        Long count = redissonClient.getAtomicLong(countKey).get();
        return count != null && count > 3;
    }

    /**
     * 增加key的访问计数
     */
    public void incrementAccessCount(String cacheKey) {
        String countKey = RagConstant.CACHE_RATE_LIMIT + "hot:" + cacheKey;
        redissonClient.getAtomicLong(countKey).incrementAndGet();
    }
}