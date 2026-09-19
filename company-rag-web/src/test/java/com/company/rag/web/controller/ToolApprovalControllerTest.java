package com.company.rag.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.agent.approve.ToolApprovalRequest;
import com.company.rag.agent.approve.ToolApprovalRequestMapper;
import com.company.rag.agent.approve.ToolApprovalService;
import com.company.rag.common.model.R;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolApprovalControllerTest {

    @Mock
    ToolApprovalService toolApprovalService;

    @Mock
    ToolApprovalRequestMapper mapper;

    @InjectMocks
    ToolApprovalController controller;

    @Test
    void pending_rejectsWithoutTenantHeader() {
        assertThrows(IllegalArgumentException.class, () -> controller.pending(null));
    }

    @Test
    void pending_filtersByTenantAndStatus() {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setId(1L);
        req.setToolName("execute");
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
    void approve_delegatesAndReturnsOk() {
        when(toolApprovalService.approve(anyLong())).thenReturn(true);
        R<Void> r = controller.approve(1L, 7L);
        verify(toolApprovalService).approve(1L);
        assertEquals(200, r.getCode());
    }

    @Test
    void approve_delegatesAndReturnsFail_whenNotPending() {
        when(toolApprovalService.approve(anyLong())).thenReturn(false);
        R<Void> r = controller.approve(1L, 7L);
        assertEquals(400, r.getCode());
    }

    @Test
    void deny_rejectsWithoutTenantHeader() {
        assertThrows(IllegalArgumentException.class, () -> controller.deny(1L, "无关紧要", null));
    }

    @Test
    void deny_delegatesAndReturnsOk() {
        when(toolApprovalService.deny(anyLong(), org.mockito.ArgumentMatchers.any(String.class))).thenReturn(true);
        R<Void> r = controller.deny(1L, "原因", 7L);
        verify(toolApprovalService).deny(1L, "原因");
        assertEquals(200, r.getCode());
    }

    @Test
    void deny_usesDefaultReason_whenBlank() {
        when(toolApprovalService.deny(1L, "人工拒绝")).thenReturn(true);
        R<Void> r = controller.deny(1L, "   ", 7L);
        verify(toolApprovalService).deny(1L, "人工拒绝");
        assertEquals(200, r.getCode());
    }

    @Test
    void deny_delegatesAndReturnsFail_whenNotPending() {
        when(toolApprovalService.deny(anyLong(), org.mockito.ArgumentMatchers.any(String.class))).thenReturn(false);
        R<Void> r = controller.deny(1L, "原因", 7L);
        assertEquals(400, r.getCode());
    }
}