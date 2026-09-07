package com.company.rag.rag.cache;

import com.company.rag.common.constant.RagConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagCacheManagerTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RMapCache<Object, Object> cache;

    @Mock
    private RAtomicLong versionCounter;

    private RagCacheManager manager;

    @BeforeEach
    void setUp() {
        manager = new RagCacheManager(redissonClient);
    }

    @Test
    void currentVersion_shouldReturnIncrementedVersionAfterInvalidate() {
        // Given — 租户 1 初始版本为 0
        String versionKey = RagConstant.CACHE_DOC_VECTOR + "version:1";
        when(redissonClient.getAtomicLong(eq(versionKey))).thenReturn(versionCounter);
        when(versionCounter.get()).thenReturn(0L);

        // When — 首次读取版本
        long v1 = manager.currentVersion(1L);

        // When — 失效后版本递增
        when(versionCounter.get()).thenReturn(1L);
        manager.invalidateByTenant(1L);

        // Then — 版本号已递增
        long v2 = manager.currentVersion(1L);
        org.assertj.core.api.Assertions.assertThat(v1).isEqualTo(0L);
        org.assertj.core.api.Assertions.assertThat(v2).isEqualTo(1L);
        verify(versionCounter).incrementAndGet();
    }

    @Test
    void currentVersion_shouldReturnZeroWhenNoVersionExists() {
        // Given — 未初始化版本号（get() 返回 long 基元，语义上默认 0）
        String versionKey = RagConstant.CACHE_DOC_VECTOR + "version:9";
        when(redissonClient.getAtomicLong(eq(versionKey))).thenReturn(versionCounter);
        when(versionCounter.get()).thenReturn(0L);

        // When
        long v = manager.currentVersion(9L);

        // Then — 返回默认 0
        org.assertj.core.api.Assertions.assertThat(v).isEqualTo(0L);
    }

    @Test
    void clearAll_shouldClearEntireCache() {
        // Given
        String prefix = RagConstant.CACHE_DOC_VECTOR;
        when(redissonClient.getMapCache(eq(prefix + "search"))).thenReturn(cache);

        // When
        manager.clearAll();

        // Then
        verify(cache).clear();
    }
}
