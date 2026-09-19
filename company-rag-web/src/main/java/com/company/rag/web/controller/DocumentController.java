package com.company.rag.web.controller;

import com.company.rag.common.annotation.AuditLog;
import com.company.rag.common.model.R;
import com.company.rag.document.entity.Document;
import com.company.rag.document.pipeline.DocumentPipelineService;
import com.company.rag.document.pipeline.PipelineStatusVO;
import com.company.rag.document.service.DocumentParseService;
import com.company.rag.tenant.context.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * 文档管理接口
 */
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentParseService documentParseService;
    private final DocumentPipelineService documentPipelineService;

    /**
     * 上传文档（admin 和 user 可操作，viewer 不可）
     * <p>仅完成落盘并提交异步管道，立即返回任务 ID；后续通过 /status/{taskId} 轮询结果。</p>
     */
    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @AuditLog(actionType = "UPLOAD_DOCUMENT", targetType = "document",
              detail = "'上传文档：' + arg0.getOriginalFilename()", async = true)
    public R<UUID> upload(@RequestParam("file") MultipartFile file) {
        // 从租户上下文获取租户 ID（由 TenantInterceptor 设置）
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            tenantId = 1L; // 默认租户 ID（用于开发环境）
        }
        UUID taskId = documentPipelineService.submitUpload(file, tenantId);
        return R.ok(taskId);
    }

    /**
     * 查询文档管道任务状态（越权 taskId 返回成功但无数据，不泄露他租户记录）
     */
    @GetMapping("/status/{taskId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'VIEWER')")
    public R<PipelineStatusVO> status(@PathVariable UUID taskId) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            tenantId = 1L;
        }
        return R.ok(documentPipelineService.getStatus(taskId, tenantId));
    }

    /**
     * 文档管道失败补跑（仅 FAILED 状态可重跑，admin 和 user 可操作）
     */
    @PostMapping("/{taskId}/retry-step")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @AuditLog(actionType = "RETRY_DOCUMENT", targetType = "document", targetId = "#taskId",
              detail = "'重跑文档管道：' + #taskId")
    public R<Void> retryStep(@PathVariable UUID taskId) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            tenantId = 1L;
        }
        documentPipelineService.retryStep(taskId, tenantId);
        return R.ok();
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
