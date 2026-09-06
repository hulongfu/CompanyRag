package com.company.rag.tenant.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 审计日志实体
 * <p>
 * 平台级表，显式声明 schema=public，与 ignoreTable 豁免协同，
 * 避免 TenantLine 对跨租户 insert/select 追加 tenant_id 条件。
 */
@Data
@TableName(value = "audit_log", schema = "public")
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
