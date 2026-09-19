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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ToolApprovalService} 单元测试 —— 覆盖正常 / 边界 / 异常场景。
 *
 * <p>判定 = 高危兜底集 OR 工具自声明；approve/deny 采用原子条件更新（仅 PENDING 可决策、
 * 按影响行数判成败，天然幂等）；await 三种结果（proceed/deny/超时）。
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

    // ---------- approve（原子条件更新） ----------

    @Test
    void approve_whenPending_returnsTrueAndUpdatesStatusToExecuted() {
        // 条件更新命中 1 行 → 成功
        when(mapper.update(any(ToolApprovalRequest.class), any())).thenReturn(1);

        assertTrue(service.approve(1L));

        // 验证从 read-modify-write 改为原子条件更新：走两参 update(entity, wrapper)，而非单参 updateById
        ArgumentCaptor<ToolApprovalRequest> entity = ArgumentCaptor.forClass(ToolApprovalRequest.class);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ToolApprovalRequest>> wp =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).update(entity.capture(), wp.capture());
        assertSame(ToolApprovalStatus.EXECUTED, entity.getValue().getStatus());
        assertNotNull(entity.getValue().getDecidedAt());
        assertNotNull(wp.getValue());
    }

    @Test
    void approve_whenAlreadyDecided_returnsFalse() {
        // 条件更新命中 0 行（该单已非 PENDING）→ 忽略重复
        when(mapper.update(any(ToolApprovalRequest.class), any())).thenReturn(0);

        assertFalse(service.approve(1L));
    }

    @Test
    void approve_whenNotFound_returnsFalse() {
        // 条件更新命中 0 行（单不存在）→ 失败
        when(mapper.update(any(ToolApprovalRequest.class), any())).thenReturn(0);
        assertFalse(service.approve(1L));
    }

    // ---------- deny（原子条件更新） ----------

    @Test
    void deny_whenPending_returnsTrueAndSetsRejected() {
        when(mapper.update(any(ToolApprovalRequest.class), any())).thenReturn(1);

        assertTrue(service.deny(1L, "业务原因"));

        ArgumentCaptor<ToolApprovalRequest> entity = ArgumentCaptor.forClass(ToolApprovalRequest.class);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ToolApprovalRequest>> wp =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).update(entity.capture(), wp.capture());
        assertSame(ToolApprovalStatus.DENIED, entity.getValue().getStatus());
        assertSame("业务原因", entity.getValue().getResult());
        assertNotNull(entity.getValue().getDecidedAt());
        assertNotNull(wp.getValue());
    }

    @Test
    void deny_whenAlreadyDecided_returnsFalse() {
        when(mapper.update(any(ToolApprovalRequest.class), any())).thenReturn(0);
        assertFalse(service.deny(1L, "原因"));
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

        ToolApprovalService.ApprovalVerdict verdict = service.await(1L, "execute");

        assertFalse(verdict.shouldProceed());
        assertNotNull(verdict.getDenyMessage());
        assertTrue(verdict.getDenyMessage().startsWith("审批超时"));
        // 超时应同步收敛为 DENIED（原子条件更新）
        ArgumentCaptor<ToolApprovalRequest> entity = ArgumentCaptor.forClass(ToolApprovalRequest.class);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<ToolApprovalRequest>> wp =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(mapper).update(entity.capture(), wp.capture());
        assertSame(ToolApprovalStatus.DENIED, entity.getValue().getStatus());
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
