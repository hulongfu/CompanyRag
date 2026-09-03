package com.company.rag.web.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.common.model.R;
import com.company.rag.common.security.UserContext;
import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.entity.RagSessionMeta;
import com.company.rag.rag.service.RagSessionService;
import com.company.rag.web.model.SessionCreateRequest;
import com.company.rag.web.model.SessionUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 会话管理控制器
 */
@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
public class SessionController {

    private final RagSessionService sessionService;

    /**
     * 创建新会话
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public R<RagSessionMeta> createSession(@RequestBody SessionCreateRequest request,
                                           @RequestHeader("X-Tenant-Id") Long tenantId) {
        // 从认证信息获取当前用户 ID
        Long userId = UserContext.getCurrentUserId();
        String title = request.getTitle() != null ? request.getTitle() : "新会话";
        RagSessionMeta meta = sessionService.createSession(tenantId, userId, title);
        return R.ok(meta);
    }

    /**
     * 获取会话列表（分页 + 搜索）
     */
    @GetMapping("/list")
    @PreAuthorize("isAuthenticated()")
    public R<Page<RagSessionMeta>> getSessionList(
            @RequestHeader("X-Tenant-Id") Long tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // 从认证信息获取当前用户 ID
        Long userId = UserContext.getCurrentUserId();
        Page<RagSessionMeta> result = sessionService.getSessionList(tenantId, userId, keyword, tags, page, size);
        return R.ok(result);
    }

    /**
     * 获取会话详情
     */
    @GetMapping("/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public R<List<RagSession>> getSessionDetail(@PathVariable String sessionId,
                                                 @RequestHeader("X-Tenant-Id") Long tenantId) {
        // 从认证信息获取当前用户 ID，按用户隔离会话数据
        Long userId = UserContext.getCurrentUserId();
        List<RagSession> sessions = sessionService.getSessionDetail(tenantId, userId, sessionId);
        return R.ok(sessions);
    }

    /**
     * 软删除会话
     */
    @DeleteMapping("/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public R<Void> deleteSession(@PathVariable String sessionId,
                                  @RequestHeader("X-Tenant-Id") Long tenantId) {
        // 从认证信息获取当前用户 ID，按用户隔离会话数据
        Long userId = UserContext.getCurrentUserId();
        sessionService.deleteSession(tenantId, userId, sessionId);
        return R.ok();
    }

    /**
     * 更新会话信息
     */
    @PutMapping("/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public R<Void> updateSession(@PathVariable String sessionId,
                                  @RequestBody SessionUpdateRequest request,
                                  @RequestHeader("X-Tenant-Id") Long tenantId) {
        // 从认证信息获取当前用户 ID，按用户隔离会话数据
        Long userId = UserContext.getCurrentUserId();
        sessionService.updateSession(tenantId, userId, sessionId, request.getTitle(), request.getTags());
        return R.ok();
    }
}