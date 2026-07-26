package com.company.rag.rag.cache;

import com.company.rag.common.event.DocumentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CacheInvalidationListener {

    private final RagCacheManager cacheManager;

    @EventListener
    public void onDocumentEvent(DocumentEvent event) {
        log.info("收到文档事件 | tenantId={} | documentId={} | type={}",
                event.getTenantId(), event.getDocumentId(), event.getEventType());
        try {
            cacheManager.invalidateByTenant(event.getTenantId());
        } catch (Exception e) {
            // 缓存失效异常不应影响文档操作
            log.error("缓存失效失败 | tenantId={} | error={}", event.getTenantId(), e.getMessage());
        }
    }
}
