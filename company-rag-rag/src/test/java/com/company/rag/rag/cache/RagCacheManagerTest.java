package com.company.rag.rag.cache;

import com.company.rag.common.constant.RagConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;

import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagCacheManagerTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RMapCache<Object, Object> cache;

    private RagCacheManager manager;

    @BeforeEach
    void setUp() {
        manager = new RagCacheManager(redissonClient);
    }

    @Test
    void invalidateByTenant_shouldRemoveMatchingKeys() {
        // Given — 租户 1 和租户 2 的缓存 key（新格式：tenantId:query:topK:strategy:rerank）
        String prefix = RagConstant.CACHE_DOC_VECTOR;
        String key1 = prefix + "1:test query:10:HYBRID:1";
        String key2 = prefix + "1:another query:10:HYBRID:0";
        String key3 = prefix + "2:other tenant query:10:HYBRID:1";
        when(redissonClient.getMapCache(eq(prefix + "search"))).thenReturn(cache);
        when(cache.keySet()).thenReturn(Set.of(key1, key2, key3));

        // When
        manager.invalidateByTenant(1L);

        // Then — 只删除租户 1 的 key
        verify(cache).remove(eq(key1));
        verify(cache).remove(eq(key2));
        verify(cache, never()).remove(eq(key3));
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
