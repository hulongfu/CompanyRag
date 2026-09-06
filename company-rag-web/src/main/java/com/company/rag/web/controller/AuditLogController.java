package com.company.rag.web.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.common.model.R;
import com.company.rag.tenant.model.AuditLog;
import com.company.rag.tenant.service.AuditLogQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 平台管理员只读查询审计日志。仅 ROLE_ADMIN 可访问（@PreAuthorize 需 @EnableMethodSecurity）。
 * 审计不可变，本接口仅提供 GET 分页查询，无新增/删除/修改。
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<Page<AuditLog>> query(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        Page<AuditLog> result = auditLogQueryService.query(
                tenantId, userId, actionType, startTime, endTime, page, pageSize);
        return R.ok(result);
    }
}