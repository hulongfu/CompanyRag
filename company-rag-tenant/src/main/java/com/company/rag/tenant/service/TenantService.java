package com.company.rag.tenant.service;

import com.company.rag.tenant.model.Tenant;
import com.company.rag.tenant.model.User;

import java.util.List;

/**
 * 租户服务接口
 */
public interface TenantService {

    Tenant getByCode(String tenantCode);

    Tenant getById(Long id);

    /**
     * Schema隔离：为租户创建独立的Schema
     */
    void createTenantSchema(Tenant tenant);

    List<User> getUsersByTenant(Long tenantId);

    /**
     * 创建租户并初始化 Schema 和默认管理员用户
     */
    Tenant createTenantWithSchema(Tenant tenant);

    /**
     * 获取所有租户列表
     */
    java.util.List<Tenant> getAllTenants();

    /**
     * 删除租户及其 Schema（级联删除所有数据）
     * @param tenantId 租户 ID
     * @return 是否删除成功
     */
    boolean deleteTenantWithSchema(Long tenantId);

    /**
     * 根据用户名加载安全用户（用于 Spring Security 认证）
     */
    com.company.rag.common.security.SecurityUser loadSecurityUserByUsername(String username);

    /**
     * 根据用户 ID 加载安全用户（用于 JWT 认证后获取上下文）
     */
    com.company.rag.common.security.SecurityUser loadSecurityUserById(Long userId);

    /**
     * 记录审计日志
     */
    void recordAuditLog(String actionType, String targetType, String targetId, String detail);
}
