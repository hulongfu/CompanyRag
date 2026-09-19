package com.company.rag.agent.approve;

import com.company.rag.agent.config.ApprovalProperties;
import com.company.rag.agent.tool.AgentTool;
import com.company.rag.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ToolApprovalService} 单元测试 —— 覆盖正常 / 边界 / 异常场景。
 *
 * <p>判定 = 高危兜底集 OR 工具自声明；approve/deny 幂等；await 三种结果（proceed/deny/超时）。
 * 使用 Mockito mock Mapper 与 Properties，不依赖 DB。
 */
class ToolApprovalServiceTest {

    private ToolApprovalRequestMapper mapper;
    private ApprovalProperties props;
    private ToolApprovalService service;

    @BeforeEach
    void setUp() {
        mapper = org.mockito.Mockito.mock(ToolApprovalRequestMapper.class);
        props = new ApprovalProperties();
        props.setPollIntervalMs(10); // 测试用短轮询间隔
        service = new ToolApprovalService(mapper, props);
        TenantContext.setTenantId(1001L);
        TenantContext.setUserId(42L);
        TenantContext.setSessionId("sess-1");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---------- needsApproval ----------

    @Test
    void needsApproval_whenDisabled_returnsFalse() {
        props.setEnabled(false); // 默认即关闭
        AgentTool tool = mockTool(true);
        assertFalse(service.needsApproval("execute", tool));
    }

    @Test
    void needsApproval_highRiskFallback_hitReturnsTrue() {
        props.setEnabled(true);
        props.setHighRiskTools("execute");
        AgentTool tool = mockTool(false); // 自声明 false，但命中高危兜底集
        assertTrue(service.needsApproval("execute", tool));
    }

    @Test
    void needsApproval_selfDeclared_hitReturnsTrue() {
        props.setEnabled(true);
        AgentTool tool = mockTool(true);
        assertTrue(service.needsApproval("database_query", tool));
    }

    @Test
    void needsApproval_neither_hitReturnsFalse() {
        props.setEnabled(true);
        AgentTool tool = mockTool(false);
        assertFalse(service.needsApproval("database_query", tool));
    }

    // ---------- createRequest ----------

    @Test
    void createRequest_insertsWithThreadContextAndPending() {
        ToolApprovalRequest inserted = new ToolApprovalRequest();
        inserted.setId(9L);
        when(mapper.insert(any(ToolApprovalRequest.class))).thenAnswer(inv -> {
            ToolApprovalRequest arg = inv.getArgument(0);
            arg.setId(9L);
            return 1;
        });

        ToolApprovalRequest req = service.createRequest("execute", Map.of("command", "ls"));

        ArgumentCaptor<ToolApprovalRequest> captor = ArgumentCaptor.forClass(ToolApprovalRequest.class);
        verify(mapper).insert(captor.capture());
        ToolApprovalRequest saved = captor.getValue();
        assertEquals(1001L, saved.getTenantId());
        assertEquals(42L, saved.getRequesterUserId());
        assertSame("sess-1", saved.getSessionId());
        assertSame(ToolApprovalStatus.PENDING, saved.getStatus());
        assertTrue(saved.getArgsJson().contains("ls"));
        assertEquals(9L, req.getId());
    }

    // ---------- approve / deny ----------

    @Test
    void approve_whenPending_returnsTrueAndMarksExecuted() {
        props.setEnabled(true);
        ToolApprovalRequest row = pendingRow();
        when(mapper.selectById(1L)).thenReturn(row);

        assertTrue(service.approve(1L));
        assertSame(ToolApprovalStatus.EXECUTED, row.getStatus());
        assertTrue(row.getDecidedAt() != null);
        verify(mapper).updateById(row);
    }

    @Test
    void approve_whenAlreadyDecided_returnsFalse() {
        ToolApprovalRequest row = pendingRow();
        row.setStatus(ToolApprovalStatus.DENIED);
        when(mapper.selectById(1L)).thenReturn(row);

        assertFalse(service.approve(1L));
        verify(mapper, never()).updateById(any(ToolApprovalRequest.class));
    }

    @Test
    void approve_whenNotFound_returnsFalse() {
        when(mapper.selectById(1L)).thenReturn(null);
        assertFalse(service.approve(1L));
    }

    @Test
    void deny_whenPending_setsRejectedWithReason() {
        ToolApprovalRequest row = pendingRow();
        when(mapper.selectById(1L)).thenReturn(row);

        assertTrue(service.deny(1L, "业务原因"));
        assertSame(ToolApprovalStatus.DENIED, row.getStatus());
        assertSame("业务原因", row.getResult());
        assertTrue(row.getDecidedAt() != null);
    }

    // ---------- await ----------

    @Test
    void await_whenExecuted_returnsProceed() {
        props.setTimeoutSeconds(30);
        ToolApprovalRequest row = pendingRow();
        row.setStatus(ToolApprovalStatus.EXECUTED);
        when(mapper.selectById(1L)).thenReturn(row);

        ToolApprovalService.ApprovalVerdict verdict = service.await(1L, "execute");
        assertTrue(verdict.shouldProceed());
        assertNull(verdict.getDenyMessage());
    }

    @Test
    void await_whenDenied_returnsDenyWithReason() {
        props.setTimeoutSeconds(30);
        ToolApprovalRequest row = pendingRow();
        row.setStatus(ToolApprovalStatus.DENIED);
        row.setResult("拒绝此命令");
        when(mapper.selectById(1L)).thenReturn(row);

        ToolApprovalService.ApprovalVerdict verdict = service.await(1L, "execute");
        assertFalse(verdict.shouldProceed());
        assertSame("拒绝此命令", verdict.getDenyMessage());
    }

    @Test
    void await_whenTimeout_returnsDenyAndConverges() {
        props.setTimeoutSeconds(0); // 立即超时
        ToolApprovalRequest row = pendingRow();
        when(mapper.selectById(1L)).thenReturn(row);

        ToolApprovalService.ApprovalVerdict verdict = service.await(1L, "execute");
        assertFalse(verdict.shouldProceed());
        // 超时应同步收敛为 DENIED
        assertSame(ToolApprovalStatus.DENIED, row.getStatus());
        verify(mapper).updateById(row);
    }

    @Test
    void await_whenNotFound_returnsDeny() {
        props.setTimeoutSeconds(30);
        when(mapper.selectById(1L)).thenReturn(null);

        ToolApprovalService.ApprovalVerdict verdict = service.await(1L, "execute");
        assertFalse(verdict.shouldProceed());
    }

    private ToolApprovalRequest pendingRow() {
        ToolApprovalRequest row = new ToolApprovalRequest();
        row.setId(1L);
        row.setTenantId(1001L);
        row.setToolName("execute");
        row.setStatus(ToolApprovalStatus.PENDING);
        return row;
    }

    private AgentTool mockTool(boolean requiresApproval) {
        AgentTool tool = org.mockito.Mockito.mock(AgentTool.class);
        org.mockito.Mockito.when(tool.requiresApproval()).thenReturn(requiresApproval);
        return tool;
    }
}