package com.company.rag.web.controller;

import com.company.rag.common.annotation.AuditLog;
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
    @AuditLog(actionType = "CLEAR_CACHE", targetType = "cache", detail = "'清空租户缓存：tenantId=' + #tenantId")
    public R<Void> clearByTenant(@RequestParam Long tenantId) {
        cacheManager.invalidateByTenant(tenantId);
        return R.ok();
    }

    /**
     * 清空所有 RAG 缓存
     */
    @PostMapping("/clearAll")
    @AuditLog(actionType = "CLEAR_ALL_CACHE", targetType = "cache", detail = "'清空所有缓存'")
    public R<Void> clearAll() {
        cacheManager.clearAll();
        return R.ok();
    }
}
