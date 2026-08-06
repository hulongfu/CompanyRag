package com.company.rag.web.controller;

import com.company.rag.common.annotation.AuditLog;
import com.company.rag.common.model.R;
import com.company.rag.document.entity.Document;
import com.company.rag.document.service.DocumentParseService;
import com.company.rag.tenant.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理接口
 */
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentParseService documentParseService;

    /**
     * 上传文档（admin 和 user 可操作，viewer 不可）
     */
    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    public R<Document> upload(@RequestParam("file") MultipartFile file) {
        // 从租户上下文获取租户 ID（由 TenantInterceptor 设置）
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            tenantId = 1L; // 默认租户 ID（用于开发环境）
        }
        Document doc = documentParseService.uploadAndParse(file, tenantId);
        return R.ok(doc);
    }

    @GetMapping("/list")
    public R<List<Document>> list() {
        // 从租户上下文获取租户 ID
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            tenantId = 1L; // 默认租户 ID（用于开发环境）
        }
        return R.ok(documentParseService.listDocuments(tenantId));
    }

    /**
     * 删除文档（admin 和 user 可操作，viewer 不可）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @AuditLog(actionType = "DELETE_DOCUMENT", targetType = "document", targetId = "#id", detail = "'删除文档：ID=' + #id")
    public R<Void> delete(@PathVariable Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            tenantId = 1L;
        }
        documentParseService.deleteDocument(id, tenantId);
        return R.ok();
    }
}
