package com.company.rag.tenant.service;

import com.company.rag.common.model.AuditLogContext;
import com.company.rag.common.service.AuditLogService;
import com.company.rag.tenant.mapper.AuditLogMapper;
import com.company.rag.tenant.model.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * AuditLogServiceImpl 单测：覆盖同步落库、失败降级、兼容入口、异步委托
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceImplTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @Mock
    private AuditLogAsyncWriter auditLogAsyncWriter;

    private AuditLogServiceImpl auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogServiceImpl(auditLogMapper, auditLogAsyncWriter);
    }

    @Test
    void recordSyncPersistsAndMapFieldsCorrectly() {
        AuditLogContext ctx = AuditLogContext.builder()
                .actionType("EXECUTE_TOOL")
                .targetType("tool")
                .targetId("tool-1")
                .detail("执行了某种工具")
                .tenantId("t1")
                .userId(100L)
                .ipAddress("192.168.1.10")
                .build();

        auditLogService.record(ctx);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog log = captor.getValue();
        assertEquals("EXECUTE_TOOL", log.getActionType());
        assertEquals("tool", log.getTargetType());
        assertEquals("tool-1", log.getTargetId());
        assertEquals("执行了某种工具", log.getDetail());
        assertEquals("t1", log.getTenantId());
        assertEquals(100L, log.getUserId());
        assertEquals("192.168.1.10", log.getIpAddress());
        assertNotNull(log.getCreatedAt(), "createdAt 应在落库时填充当前时间");
    }

    @Test
    void recordSyncSwallowsPersistenceFailure() {
        doThrow(new RuntimeException("db down")).when(auditLogMapper).insert(any(AuditLog.class));

        // 审计失败不得抛给主流程
        assertDoesNotThrow(() -> auditLogService.record(
                AuditLogContext.builder().actionType("LOGIN").tenantId("t1").userId(1L).build()));
    }

    @Test
    void recordAuditLogDeprecatedCompatEntryDelegatesToRecord() {
        auditLogService.recordAuditLog("LOGIN", "user", "u1", "登录");

        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        auditLogService.recordAuditLog("LOGIN2", "user", "u2", "登录2");
        // 通过 mapper 调用验证已转换为同步 record 并落库（兼容入口默认走同步）
        ArgumentCaptor<AuditLog> logCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, org.mockito.Mockito.times(2)).insert(logCaptor.capture());
        AuditLog first = logCaptor.getAllValues().get(0);
        assertEquals("LOGIN", first.getActionType());
        assertEquals("u1", first.getTargetId());
        assertEquals("登录", first.getDetail());
        // 兼容入口无归属信息：tenantId/userId 为 null 兜底
        assertEquals(null, first.getTenantId());
        assertEquals(null, first.getUserId());
    }

    @Test
    void recordAsyncDelegatesToWriter() {
        when(auditLogAsyncWriter.offer(any(AuditLogContext.class))).thenReturn(true);

        AuditLogContext ctx = AuditLogContext.builder()
                .actionType("DATABASE_QUERY").tenantId("t1").userId(2L).build();
        auditLogService.recordAsync(ctx);

        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogAsyncWriter).offer(captor.capture());
        assertEquals("DATABASE_QUERY", captor.getValue().getActionType());
        verifyNoMoreInteractions(auditLogAsyncWriter);
    }

    @Test
    void asyncQueueFullLogsWarningAndNoThrow() {
        when(auditLogAsyncWriter.offer(any(AuditLogContext.class))).thenReturn(false);

        assertDoesNotThrow(() -> auditLogService.recordAsync(
                AuditLogContext.builder().actionType("MCP_TOOL").tenantId("t1").build()));
    }
}