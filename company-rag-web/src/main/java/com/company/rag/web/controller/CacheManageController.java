package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.rag.cache.RagCacheManager;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
public class CacheManageController {

    private final RagCacheManager cacheManager;

    /**
     * 清空指定租户的缓存
     */
    @PostMapping("/clear")
    public R<Void> clearByTenant(@RequestParam Long tenantId) {
        cacheManager.invalidateByTenant(tenantId);
        return R.ok();
    }

    /**
     * 清空所有 RAG 缓存
     */
    @PostMapping("/clearAll")
    public R<Void> clearAll() {
        cacheManager.clearAll();
        return R.ok();
    }
}
