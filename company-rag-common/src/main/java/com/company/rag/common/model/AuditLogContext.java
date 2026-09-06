package com.company.rag.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审计日志上下文（跨模块传输载体，不含敏感输出/密钥）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogContext {

    /** 操作类型：LOGIN / DELETE_DOCUMENT / EXECUTE_TOOL / DATABASE_QUERY / DOWNLOAD / MCP_TOOL 等 */
    private String actionType;

    /** 目标类型：document / user / tenant / tool */
    private String targetType;

    /** 目标 ID */
    private String targetId;

    /** 操作本体，不记输出/密钥 */
    private String detail;

    /** 归属租户 ID（String 形态，与 public.audit_log.tenant_id 一致） */
    private String tenantId;

    /** 操作者用户 ID */
    private Long userId;

    /** 客户端 IP */
    private String ipAddress;
}