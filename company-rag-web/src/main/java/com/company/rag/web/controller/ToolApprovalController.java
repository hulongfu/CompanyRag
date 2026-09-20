package com.company.rag.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.agent.approve.ToolApprovalRequest;
import com.company.rag.agent.approve.ToolApprovalRequestMapper;
import com.company.rag.agent.approve.ToolApprovalService;
import com.company.rag.agent.approve.ToolApprovalStatus;
import com.company.rag.common.model.R;
import com.company.rag.common.security.UserContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具审批 Controller：pending / approve / deny。
 * 仿真 EvalController：按鉴权用户 + X-Tenant-Id 头隔离租户（铁律：租户 ID 仅取请求头）。
 */
@Slf4j
@RestController
@RequestMapping("/api/tool-approval")
@RequiredArgsConstructor
public class ToolApprovalController {

    private final ToolApprovalService toolApprovalService;
    private final ToolApprovalRequestMapper mapper;

    /** 待审批列表（当前租户 + 当前用户 PENDING 单，含参数快照供复核） */
    @GetMapping("/pending")
    @PreAuthorize("isAuthenticated()")
    public R<List<ToolApprovalRequest>> pending(
            @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        if (headerTenantId == null) {
            throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
        }
        List<ToolApprovalRequest> list = mapper.selectList(new LambdaQueryWrapper<ToolApprovalRequest>()
                .eq(ToolApprovalRequest::getTenantId, headerTenantId)
                .eq(ToolApprovalRequest::getRequesterUserId, UserContext.getCurrentUserId())
                .eq(ToolApprovalRequest::getStatus, ToolApprovalStatus.PENDING)
                .orderByDesc(ToolApprovalRequest::getRequestedAt));
        return R.ok(list);
    }

    /**
     * 批准（唤醒等待线程继续执行）。
     * 防越权：仅当前租户 schema 内、由当前用户发起的单可被其批准。
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("isAuthenticated()")
    public R<Void> approve(@PathVariable Long id,
                           @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        if (headerTenantId == null) {
            throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
        }
        ToolApprovalRequest req = mapper.selectById(id);
        if (req == null || !req.getTenantId().equals(headerTenantId)
                || !req.getRequesterUserId().equals(UserContext.getCurrentUserId())) {
            log.warn("[APPROVAL] 越权/无效批准请求：id={}, 期望租户={}, 实际租户={}, 期望用户={}, 实际用户={}",
                    id, headerTenantId, req == null ? null : req.getTenantId(),
                    UserContext.getCurrentUserId(), req == null ? null : req.getRequesterUserId());
            return R.fail("无权审批该请求：非当前租户/用户的审批单");
        }
        boolean ok = toolApprovalService.approve(id);
        return ok ? R.ok() : R.fail("审批失败：单不存在或已被决策");
    }

    /**
     * 拒绝。
     * 防越权：仅当前租户 schema 内、由当前用户发起的单可被其拒绝。
     */
    @PostMapping("/{id}/deny")
    @PreAuthorize("isAuthenticated()")
    public R<Void> deny(@PathVariable Long id,
                        @RequestBody(required = false) String reason,
                        @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        if (headerTenantId == null) {
            throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
        }
        ToolApprovalRequest req = mapper.selectById(id);
        if (req == null || !req.getTenantId().equals(headerTenantId)
                || !req.getRequesterUserId().equals(UserContext.getCurrentUserId())) {
            log.warn("[APPROVAL] 越权/无效拒绝请求：id={}, 期望租户={}, 实际租户={}, 期望用户={}, 实际用户={}",
                    id, headerTenantId, req == null ? null : req.getTenantId(),
                    UserContext.getCurrentUserId(), req == null ? null : req.getRequesterUserId());
            return R.fail("无权审批该请求：非当前租户/用户的审批单");
        }
        boolean ok = toolApprovalService.deny(
                id, reason == null || reason.isBlank() ? "人工拒绝" : reason);
        return ok ? R.ok() : R.fail("拒绝失败：单不存在或已被决策");
    }
}