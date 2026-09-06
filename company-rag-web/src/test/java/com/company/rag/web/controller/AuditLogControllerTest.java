package com.company.rag.web.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.common.model.R;
import com.company.rag.tenant.model.AuditLog;
import com.company.rag.tenant.service.AuditLogQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogController 单测：参数透传、默认 page/pageSize、（@PreAuthorize 由 SecurityConfig 集成测试覆盖）
 */
class AuditLogControllerTest {

    private AuditLogQueryService auditLogQueryService;
    private AuditLogController controller;

    @BeforeEach
    void setUp() {
        auditLogQueryService = mock(AuditLogQueryService.class);
        // @RequiredArgsConstructor：仅一个 final 字段 auditLogQueryService
        controller = new AuditLogController(auditLogQueryService);
    }

    @Test
    void queryForwardAllParams() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 12, 31, 23, 59);
        Page<AuditLog> page = new Page<>(1, 20);
        when(auditLogQueryService.query(any(), any(), any(), any(), any(), eq(1L), eq(20L))).thenReturn(page);

        R<Page<AuditLog>> result = controller.query("42", 9L, "LOGIN", start, end, 1L, 20L);

        ArgumentCaptor<String> tenantCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> actionCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditLogQueryService)
                .query(tenantCaptor.capture(), userIdCaptor.capture(), actionCaptor.capture(),
                        startCaptor.capture(), endCaptor.capture(), eq(1L), eq(20L));

        assertEquals("42", tenantCaptor.getValue());
        assertEquals(9L, userIdCaptor.getValue());
        assertEquals("LOGIN", actionCaptor.getValue());
        assertEquals(start, startCaptor.getValue());
        assertEquals(end, endCaptor.getValue());
        assertEquals(200, result.getCode());
        assertEquals(page, result.getData());
    }

    @Test
    void queryWithDefaultsWhenParamsMissing() {
        Page<AuditLog> page = new Page<>(1, 20);
        when(auditLogQueryService.query(any(), any(), any(), any(), any(), eq(1L), eq(20L))).thenReturn(page);

        controller.query(null, null, null, null, null, 1L, 20L);

        verify(auditLogQueryService).query(null, null, null, null, null, 1L, 20L);
    }
}