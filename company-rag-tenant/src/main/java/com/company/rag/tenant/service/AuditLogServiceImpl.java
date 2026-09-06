package com.company.rag.tenant.service;

import com.company.rag.common.model.AuditLogContext;
import com.company.rag.common.service.AuditLogService;
import com.company.rag.tenant.mapper.AuditLogMapper;
import com.company.rag.tenant.model.AuditLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 审计日志服务实现
 * <p>
 * 双轨落库：
 * - {@link #record(AuditLogContext)}：同步 + REQUIRES_NEW（独立于主事务），供管理类关键操作即时落库。
 * - {@link #recordAsync(AuditLogContext)}：入有界队列批量落库（委托 {@link AuditLogAsyncWriter}）。
 * 所有落库经 {@link AuditLogMapper} 自营，且 {@code AuditLog} 的 @TableName 为 {@code public.audit_log}，
 * 天然规避 search_path 残留。审计失败仅 log 绝不抛给主流程。
 */
@Slf4j
@Service
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private final AuditLogAsyncWriter auditLogAsyncWriter;

    public AuditLogServiceImpl(AuditLogMapper auditLogMapper, AuditLogAsyncWriter auditLogAsyncWriter) {
        this.auditLogMapper = auditLogMapper;
        this.auditLogAsyncWriter = auditLogAsyncWriter;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditLogContext ctx) {
        try {
            auditLogMapper.insert(toEntity(ctx));
        } catch (Exception e) {
            // 审计失败仅 log，绝不抛给主流程
            log.error("审计日志落库失败：action={}", ctx.getActionType(), e);
        }
    }

    @Override
    public void recordAsync(AuditLogContext ctx) {
        if (!auditLogAsyncWriter.offer(ctx)) {
            log.warn("审计异步队列已满，丢弃记录：action={}", ctx.getActionType());
        }
    }

    @Override
    @Deprecated
    public void recordAuditLog(String actionType, String targetType, String targetId, String detail) {
        // 兼容入口：无归属信息（tenantId/userId 为 null 兜底），走同步 record
        record(AuditLogContext.builder()
                .actionType(actionType)
                .targetType(targetType)
                .targetId(targetId)
                .detail(detail)
                .build());
    }

    private AuditLog toEntity(AuditLogContext ctx) {
        AuditLog log = new AuditLog();
        log.setTenantId(ctx.getTenantId());
        log.setUserId(ctx.getUserId());
        log.setActionType(ctx.getActionType());
        log.setTargetType(ctx.getTargetType());
        log.setTargetId(ctx.getTargetId());
        log.setDetail(ctx.getDetail());
        log.setIpAddress(ctx.getIpAddress());
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }
}