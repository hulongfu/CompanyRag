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
 * RAG 缓存管理器
 * 基于 Redis Redisson 的 RMapCache 实现
 * 缓存策略：
 * - 缓存 Key：version:tenantId:query:topK:strategy:rerank（避免不同参数组合错误命中）
 * - 相同查询和参数的检索结果缓存 5 分钟（TTL）
 * - 文档变更时通过租户级事件递增租户版本号触发失效，旧版本 key 交由 TTL 回收
 * - 热点问题自动延长 TTL 至 30 分钟
 *
 * 失效机制（租户版本号）：
 * - 每个租户维护一个原子版本号（AtomicLong），缓存的检索 key 拼接当前版本号；
 * - invalidateByTenant 仅递增版本号（O(1)），不再全桶遍历删除；
 * - 版本号变更后，旧版本 key 无法再被命中，天然消除"失效 vs 并发写缓存"的竞态
 *   （旧 key 由 5 分钟 TTL 兜底回收，无锁、无阻塞）。
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
    // 租户版本号 key 前缀：用于失效缓存（递增版本使旧 key 失效）
    private static final String VERSION_KEY_PREFIX = RagConstant.CACHE_DOC_VECTOR + "version:";

    /**
     * 获取缓存map实例
     */
    private RMapCache<String, RagResult> getCache() {
        return redissonClient.getMapCache(RagConstant.CACHE_DOC_VECTOR + "search");
    }

    /**
     * 获取当前租户的缓存版本号（默认 0）。
     * 检索 key 拼接该版本号，版本越新越先命中；版本递增后旧版本 key 自动失效。
     */
    public long currentVersion(Long tenantId) {
        return redissonClient.getAtomicLong(versionKey(tenantId)).get();
    }

    /**
     * 租户版本号 key。
     */
    private String versionKey(Long tenantId) {
        return VERSION_KEY_PREFIX + tenantId;
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
     * 失效指定租户的所有缓存。
     * 通过递增租户版本号实现（O(1)）：检索 key 拼接版本号，版本变更后旧 key 无法命中，
     * 由 TTL(5min) 兜底回收。避免全桶 keySet() 遍历删除的性能开销与并发写缓存竞态。
     */
    public void invalidateByTenant(Long tenantId) {
        try {
            redissonClient.getAtomicLong(versionKey(tenantId)).incrementAndGet();
            log.info("租户缓存版本递增触发失效 | tenantId={}", tenantId);
        } catch (Exception e) {
            log.error("租户缓存失效失败 | tenantId={} | error={}", tenantId, e.getMessage());
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