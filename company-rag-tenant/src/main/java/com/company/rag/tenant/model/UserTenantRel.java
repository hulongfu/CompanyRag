package com.company.rag.tenant.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户 - 租户关联表实体
 */
@Data
@TableName("sys_user_tenant_rel")
public class UserTenantRel {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long userId;
    
    private Long tenantId;
}
