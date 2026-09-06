package com.company.rag.agent.tool;

import com.company.rag.agent.service.DownloadService;
import com.company.rag.common.model.AuditLogContext;
import com.company.rag.common.service.AuditLogService;
import com.company.rag.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TASK-005 工具/技能/MCP 风险动作异步审计埋点单元测试。
 *
 * 归统一取自 TenantContext.getTenantId()/getUserId()：
 * - ExecuteTool.executeCommand 执行放行命令 → recordAsync EXECUTE_TOOL
 * - ExecuteTool.executeCommand 拒绝命令 → 也 recordAsync（detail=拒绝原因）
 * - DatabaseQueryTool.queryDatabase 成功 → recordAsync DATABASE_QUERY
 * - DownloadTool.execute 成功 → recordAsync DOWNLOAD
 * - AgentToolRegistry.executeTool 统一入口 → recordAsync MCP_TOOL
 */
class ToolAuditTest {

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = mock(AuditLogService.class);
        TenantContext.setTenantId(7L);
        TenantContext.setUserId(9L);
        TenantContext.setSchema("tenant_1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void executeToolAllowsCommandAndRecordsAudit() {
        ExecuteTool tool = new ExecuteTool();
        ReflectionTestUtils.setField(tool, "pythonExecPath", "D:/test/venv/Scripts/python.exe");
        ReflectionTestUtils.setField(tool, "defaultWorkDir", "D:/tmp");
        ReflectionTestUtils.setField(tool, "auditLogService", auditLogService);

        String result = tool.executeCommand("echo 你好");

        assertNotNull(result);
        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).recordAsync(captor.capture());
        AuditLogContext ctx = captor.getValue();
        assertEquals("EXECUTE_TOOL", ctx.getActionType());
        assertEquals("execute", ctx.getTargetId());
        assertTrue(ctx.getDetail().contains("echo 你好"));
        assertEquals(9L, ctx.getUserId());
        assertEquals("7", ctx.getTenantId());
    }

    @Test
    void executeToolRejectedCommandAlsoRecordsAudit() {
        ExecuteTool tool = new ExecuteTool();
        ReflectionTestUtils.setField(tool, "pythonExecPath", "D:/test/venv/Scripts/python.exe");
        ReflectionTestUtils.setField(tool, "defaultWorkDir", "D:/tmp");
        ReflectionTestUtils.setField(tool, "auditLogService", auditLogService);

        String result = tool.executeCommand("rm -rf /");

        assertTrue(result.startsWith("错误："));
        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).recordAsync(captor.capture());
        AuditLogContext ctx = captor.getValue();
        assertEquals("EXECUTE_TOOL", ctx.getActionType());
        assertTrue(ctx.getDetail().contains("rm -rf /"));
    }

    @Test
    void databaseQueryRecordsAuditOnSuccess() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList("tenant_1.orders LIMIT 100"))
                .thenReturn(List.of(Map.of("id", 1)));
        DatabaseQueryTool tool = new DatabaseQueryTool(jdbcTemplate);
        ReflectionTestUtils.setField(tool, "auditLogService", auditLogService);

        tool.queryDatabase("SELECT * FROM orders", 100);

        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).recordAsync(captor.capture());
        AuditLogContext ctx = captor.getValue();
        assertEquals("DATABASE_QUERY", ctx.getActionType());
        assertTrue(ctx.getDetail().contains("orders"));
        assertEquals(9L, ctx.getUserId());
        assertEquals("7", ctx.getTenantId());
    }

    @Test
    void downloadRecordsAuditOnSuccess() {
        DownloadService downloadService = mock(DownloadService.class);
        when(downloadService.createDownloadFile(any(), any(), any(), any(), any()))
                .thenReturn("file-1");
        DownloadTool tool = new DownloadTool(downloadService);
        ReflectionTestUtils.setField(tool, "auditLogService", auditLogService);

        String result = tool.execute(Map.of("content", "hello", "filename", "a.txt"));

        assertTrue(result.contains("✅"));
        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).recordAsync(captor.capture());
        AuditLogContext ctx = captor.getValue();
        assertEquals("DOWNLOAD", ctx.getActionType());
        assertTrue(ctx.getDetail().contains("a.txt"));
    }

    @Test
    void registryExecuteToolRecordsMcpAudit() {
        AgentTool tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn("github_read");
        when(tool.execute(any())).thenReturn("ok");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        ReflectionTestUtils.setField(registry, "auditLogService", auditLogService);

        String result = registry.executeTool("github_read", Map.of("repo", "x"));

        assertEquals("ok", result);
        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).recordAsync(captor.capture());
        AuditLogContext ctx = captor.getValue();
        assertEquals("MCP_TOOL", ctx.getActionType());
        assertTrue(ctx.getDetail().contains("github_read"));
        assertEquals(9L, ctx.getUserId());
        assertEquals("7", ctx.getTenantId());
    }

    @Test
    void registryExecuteToolUnknownToolDoesNotAudit() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of());
        ReflectionTestUtils.setField(registry, "auditLogService", auditLogService);

        String result = registry.executeTool("nope", Map.of());

        assertTrue(result.contains("不存在"));
        verify(auditLogService, never()).recordAsync(any());
    }

    @Test
    void registryExecuteToolRecordsAuditEvenWhenToolThrows() {
        // 尝试即记录：工具存在即落审计，即使执行抛出异常也应留痕
        AgentTool tool = mock(AgentTool.class);
        when(tool.getName()).thenReturn("github_read");
        when(tool.execute(any())).thenThrow(new RuntimeException("boom"));
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        ReflectionTestUtils.setField(registry, "auditLogService", auditLogService);

        String result = registry.executeTool("github_read", Map.of("repo", "x"));

        assertTrue(result.contains("工具执行失败"));
        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).recordAsync(captor.capture());
        assertEquals("MCP_TOOL", captor.getValue().getActionType());
    }
}