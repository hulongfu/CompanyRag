package com.company.rag.tenant.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 租户
 */
@Data
@TableName("sys_tenant")
public class Tenant {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantCode;
    private String tenantName;
    private String schemaName;       // 独立Schema名称（Schema隔离模式）
    private Integer status;           // 0-禁用 1-启用
    private String contactName;
    private String contactPhone;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
