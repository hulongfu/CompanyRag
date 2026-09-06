package com.company.rag.tenant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.tenant.mapper.AuditLogMapper;
import com.company.rag.tenant.model.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * admin 只读查询审计日志。查询平台级 public.audit_log，
 * 依赖 TenantMyBatisPlusConfig.ignoreTable 对 audit_log 的豁免，
 * 避免 TenantLine 自动追加当前租户 tenant_id 而截断跨租户视图。
 */
@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

    private final AuditLogMapper auditLogMapper;

    public Page<AuditLog> query(String tenantId, Long userId, String actionType,
                                LocalDateTime startTime, LocalDateTime endTime,
                                long page, long pageSize) {
        Page<AuditLog> p = new Page<>(page, pageSize);
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        // 字段均属平台级 audit_log（public），查询不依赖租户上下文
        qw.eq(tenantId != null && !tenantId.isBlank(), AuditLog::getTenantId, tenantId)
          .eq(userId != null, AuditLog::getUserId, userId)
          .eq(actionType != null && !actionType.isBlank(), AuditLog::getActionType, actionType)
          .ge(startTime != null, AuditLog::getCreatedAt, startTime)
          .le(endTime != null, AuditLog::getCreatedAt, endTime)
          .orderByDesc(AuditLog::getCreatedAt);
        return auditLogMapper.selectPage(p, qw);
    }
}