package com.company.rag.tenant.service.impl;

import com.company.rag.common.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * TenantServiceImpl.recordAuditLog 委托落库的单元测试。
 *
 * 验证 4 参兼容入口直接委托给 AuditLogService.recordAuditLog（无归属走同步）。
 */
class TenantServiceImplAuditTest {

    private AuditLogService auditLogService;
    private TenantServiceImpl service;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        // @RequiredArgsConstructor 顺序：tenantMapper, userMapper, userTenantRelMapper, jdbcTemplate, auditLogService
        service = new TenantServiceImpl(
                mock(com.company.rag.tenant.mapper.TenantMapper.class),
                mock(com.company.rag.tenant.mapper.UserMapper.class),
                mock(com.company.rag.tenant.mapper.UserTenantRelMapper.class),
                mock(JdbcTemplate.class),
                auditLogService);
    }

    @Test
    void recordAuditLogDelegatesToAuditLogService() {
        service.recordAuditLog("CREATE_TENANT", "TENANT", "10", "create tenant");

        verify(auditLogService).recordAuditLog(
                eq("CREATE_TENANT"), eq("TENANT"), eq("10"), eq("create tenant"));
    }
}