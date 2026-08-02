package com.company.rag.tenant.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 审计日志实体
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    private Long userId;

    private String actionType;

    private String targetType;

    private String targetId;

    private String detail;

    private String ipAddress;

    private LocalDateTime createdAt;
}
