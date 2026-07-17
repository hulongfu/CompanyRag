package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.document.entity.Document;
import com.company.rag.document.service.DocumentParseService;
import com.company.rag.tenant.context.TenantContext;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/upload")
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
}
