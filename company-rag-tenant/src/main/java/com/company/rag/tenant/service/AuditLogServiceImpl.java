package com.company.rag.tenant.service;

import com.company.rag.common.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务实现（委托给 TenantService）
 */
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final TenantService tenantService;

    @Override
    public void recordAuditLog(String actionType, String targetType, String targetId, String detail) {
        tenantService.recordAuditLog(actionType, targetType, targetId, detail);
    }
}
