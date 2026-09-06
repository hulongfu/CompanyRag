package com.company.rag.common.service;

import com.company.rag.common.model.AuditLogContext;

/**
 * 审计日志服务接口
 */
public interface AuditLogService {

    /**
     * 记录审计日志（同步 + REQUIRES_NEW，独立于主事务，失败仅 log 不抛）
     *
     * @param context 审计上下文
     */
    void record(AuditLogContext context);

    /**
     * 记录审计日志（异步：入有界队列批量落库，背压时丢弃）
     *
     * @param context 审计上下文
     */
    void recordAsync(AuditLogContext context);

    /**
     * 记录审计日志（兼容入口，无归属信息，走同步）
     *
     * @param actionType 操作类型
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @param detail     操作详情
     */
    @Deprecated
    void recordAuditLog(String actionType, String targetType, String targetId, String detail);
}