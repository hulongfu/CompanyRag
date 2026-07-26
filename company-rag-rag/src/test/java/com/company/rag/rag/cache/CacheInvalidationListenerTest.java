package com.company.rag.rag.cache;

import com.company.rag.common.event.DocumentEvent;
import com.company.rag.common.event.DocumentEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheInvalidationListenerTest {

    @Mock
    private RagCacheManager cacheManager;

    @InjectMocks
    private CacheInvalidationListener listener;

    @Test
    void onDocumentEvent_shouldInvalidateTenantCache() {
        // Given
        DocumentEvent event = new DocumentEvent(this, 1L, 100L, DocumentEventType.ADDED);

        // When
        listener.onDocumentEvent(event);

        // Then
        verify(cacheManager).invalidateByTenant(eq(1L));
    }

    @Test
    void onDocumentEvent_shouldNotThrowWhenCacheManagerFails() {
        // Given
        DocumentEvent event = new DocumentEvent(this, 1L, 100L, DocumentEventType.DELETED);
        doThrow(new RuntimeException("Redis 连接失败")).when(cacheManager).invalidateByTenant(eq(1L));

        // When & Then — 不应抛出异常
        try {
            listener.onDocumentEvent(event);
        } catch (Exception e) {
            throw new AssertionError("缓存失效异常不应向上抛出", e);
        }
        verify(cacheManager).invalidateByTenant(eq(1L));
    }
}
