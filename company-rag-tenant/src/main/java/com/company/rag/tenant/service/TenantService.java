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
}
