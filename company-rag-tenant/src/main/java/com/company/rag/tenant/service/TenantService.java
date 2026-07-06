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
}
