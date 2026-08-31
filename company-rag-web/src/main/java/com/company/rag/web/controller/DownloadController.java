package com.company.rag.web.controller;

import com.company.rag.agent.service.DownloadRecord;
import com.company.rag.agent.service.DownloadService;
import com.company.rag.common.model.R;
import com.company.rag.common.response.DownloadResponse;
import com.company.rag.common.request.DownloadRequest;
import com.company.rag.tenant.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.File;

/**
 * 下载控制器
 * 
 * 提供文件下载相关的 REST API：
 * - 生成下载文件
 * - 下载文件
 */
@Slf4j
@RestController
@RequestMapping("/api/tool")
@RequiredArgsConstructor
public class DownloadController {
    
    private final DownloadService downloadService;
    
    /**
     * 生成下载文件
     * 
     * @param request 下载请求
     * @return 下载响应（包含下载链接）
     */
    @PostMapping("/download")
    public R<DownloadResponse> createDownload(
        @RequestBody @Validated DownloadRequest request
    ) {
        // 1. 从请求中获取租户 ID
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            log.warn("未找到租户上下文，使用默认租户 ID=1");
            tenantId = 1L;
        }
        
        log.info("创建下载文件：tenantId={}, filename={}, contentType={}, contentLength={}",
            tenantId, request.getFilename(), request.getContentType(), 
            request.getContent() != null ? request.getContent().length() : 0);
        
        // 2. 生成文件
        DownloadRecord record = downloadService.createDownloadFile(
            tenantId,
            request.getContent(),
            request.getFilename(),
            request.getContentType()
        );
        
        // 3. 返回响应
        DownloadResponse response = DownloadResponse.builder()
            .fileId(record.getFileId())
            .filename(record.getFilename())
            .contentType(record.getContentType())
            .size(record.getSize())
            .downloadUrl(record.getDownloadUrl())
            .expiresAt(record.getExpiresAt().toString())
            .createdAt(record.getCreatedAt().toString())
            .downloadedCount(record.getDownloadedCount())
            .build();
        
        return R.ok(response);
    }
}
