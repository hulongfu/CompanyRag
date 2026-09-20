package com.company.rag.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.agent.approve.ToolApprovalRequest;
import com.company.rag.agent.approve.ToolApprovalRequestMapper;
import com.company.rag.agent.approve.ToolApprovalService;
import com.company.rag.common.model.R;
import com.company.rag.common.security.SecurityUser;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 工具审批 Controller 单元测试。
 * 覆盖方案①（租户 + 发起用户双过滤）：待审批列表仅展示当前用户自己的单；
 * approve/deny 仅允许当前租户 schema 内、由当前用户发起的单，防越权盲操作。
 */
@ExtendWith(MockitoExtension.class)
class ToolApprovalControllerTest {

    /** 当前登录用户（SecurityContext 注入） */
    private static final Long CURRENT_USER_ID = 42L;

    @Mock
    ToolApprovalService toolApprovalService;

    @Mock
    ToolApprovalRequestMapper mapper;

    @InjectMocks
    ToolApprovalController controller;

    @BeforeEach
    void setUpAuth() {
        // 构造已认证的 SecurityUser 并写入 SecurityContext，
        // 让 UserContext.getCurrentUserId() 返回固定 CURRENT_USER_ID。
        SecurityUser su = new SecurityUser(CURRENT_USER_ID, 7L, List.of(7L), "tester", "", "user", true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(su, null, su.getAuthorities()));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pending_rejectsWithoutTenantHeader() {
        assertThrows(IllegalArgumentException.class, () -> controller.pending(null));
    }

    @Test
    void pending_filtersByTenantAndCurrentUserAndStatus() {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setId(1L);
        req.setToolName("execute");
        req.setRequesterUserId(CURRENT_USER_ID);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(req));

        R<List<ToolApprovalRequest>> r = controller.pending(7L);

        verify(mapper).selectList(org.mockito.ArgumentMatchers.any(LambdaQueryWrapper.class));
        assertNotNull(r);
        assertEquals(1, r.getData().size());
        assertEquals("execute", r.getData().get(0).getToolName());
    }

    @Test
    void approve_rejectsWithoutTenantHeader() {
        assertThrows(IllegalArgumentException.class, () -> controller.approve(1L, null));
    }

    @Test
    void approve_delegatesAndReturnsOk_whenOwnedByCurrentUser() {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setId(1L);
        req.setTenantId(7L);
        req.setRequesterUserId(CURRENT_USER_ID);
        when(mapper.selectById(1L)).thenReturn(req);
        when(toolApprovalService.approve(anyLong())).thenReturn(true);

        R<Void> r = controller.approve(1L, 7L);

        verify(toolApprovalService).approve(1L);
        assertEquals(200, r.getCode());
    }

    @Test
    void approve_rejectsWhenNotOwnedByCurrentUser() {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setId(1L);
        req.setTenantId(7L);
        req.setRequesterUserId(999L); // 其他用户发起的单
        when(mapper.selectById(1L)).thenReturn(req);

        R<Void> r = controller.approve(1L, 7L);

        verify(toolApprovalService, never()).approve(anyLong());
        assertEquals(400, r.getCode());
    }

    @Test
    void approve_rejectsWhenTenantMismatch() {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setId(1L);
        req.setTenantId(999L); // 其他租户的单
        req.setRequesterUserId(CURRENT_USER_ID);
        when(mapper.selectById(1L)).thenReturn(req);

        R<Void> r = controller.approve(1L, 7L);

        verify(toolApprovalService, never()).approve(anyLong());
        assertEquals(400, r.getCode());
    }

    @Test
    void approve_rejectsWhenRequestNotFound() {
        when(mapper.selectById(1L)).thenReturn(null);

        R<Void> r = controller.approve(1L, 7L);

        verify(toolApprovalService, never()).approve(anyLong());
        assertEquals(400, r.getCode());
    }

    @Test
    void approve_delegatesAndReturnsFail_whenNotPending() {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setId(1L);
        req.setTenantId(7L);
        req.setRequesterUserId(CURRENT_USER_ID);
        when(mapper.selectById(1L)).thenReturn(req);
        when(toolApprovalService.approve(anyLong())).thenReturn(false);

        R<Void> r = controller.approve(1L, 7L);

        assertEquals(400, r.getCode());
    }

    @Test
    void deny_rejectsWithoutTenantHeader() {
        assertThrows(IllegalArgumentException.class, () -> controller.deny(1L, "无关紧要", null));
    }

    @Test
    void deny_delegatesAndReturnsOk_whenOwnedByCurrentUser() {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setId(1L);
        req.setTenantId(7L);
        req.setRequesterUserId(CURRENT_USER_ID);
        when(mapper.selectById(1L)).thenReturn(req);
        when(toolApprovalService.deny(anyLong(), org.mockito.ArgumentMatchers.any(String.class))).thenReturn(true);

        R<Void> r = controller.deny(1L, "原因", 7L);

        verify(toolApprovalService).deny(1L, "原因");
        assertEquals(200, r.getCode());
    }

    @Test
    void deny_usesDefaultReason_whenBlank() {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setId(1L);
        req.setTenantId(7L);
        req.setRequesterUserId(CURRENT_USER_ID);
        when(mapper.selectById(1L)).thenReturn(req);
        when(toolApprovalService.deny(1L, "人工拒绝")).thenReturn(true);

        R<Void> r = controller.deny(1L, "   ", 7L);

        verify(toolApprovalService).deny(1L, "人工拒绝");
        assertEquals(200, r.getCode());
    }

    @Test
    void deny_rejectsWhenNotOwnedByCurrentUser() {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setId(1L);
        req.setTenantId(7L);
        req.setRequesterUserId(999L); // 其他用户发起的单
        when(mapper.selectById(1L)).thenReturn(req);

        R<Void> r = controller.deny(1L, "原因", 7L);

        verify(toolApprovalService, never()).deny(anyLong(), org.mockito.ArgumentMatchers.any(String.class));
        assertEquals(400, r.getCode());
    }

    @Test
    void deny_delegatesAndReturnsFail_whenNotPending() {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setId(1L);
        req.setTenantId(7L);
        req.setRequesterUserId(CURRENT_USER_ID);
        when(mapper.selectById(1L)).thenReturn(req);
        when(toolApprovalService.deny(anyLong(), org.mockito.ArgumentMatchers.any(String.class))).thenReturn(false);

        R<Void> r = controller.deny(1L, "原因", 7L);

        assertEquals(400, r.getCode());
    }
}