package com.company.rag.common.service;

/**
 * 审计日志服务接口
 */
public interface AuditLogService {

    /**
     * 记录审计日志
     *
     * @param actionType 操作类型
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @param detail     操作详情
     */
    void recordAuditLog(String actionType, String targetType, String targetId, String detail);
}
