package com.company.rag.web.controller;

import com.company.rag.agent.service.AgentResult;
import com.company.rag.agent.service.RagAgentService;
import com.company.rag.common.model.R;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.response.ChatRequest;
import com.company.rag.rag.response.ChatResponse;
import com.company.rag.rag.service.RagSearchService;
import com.company.rag.rag.service.RagSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * 统一对话 Controller
 * 整合原有 AgentController 和 RagController，使用 Agent 模式
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {
    
    private final RagAgentService ragAgentService;
    private final RagSearchService ragSearchService;
    private final RagSessionService ragSessionService;
    
    /**
     * 统一对话入口（Agent 编排，LLM 自动决定调用工具）
     * 
     * @param request 聊天请求
     * @return 聊天响应
     */
    @PostMapping("/chat")
    @PreAuthorize("isAuthenticated()")
    public R<ChatResponse> chat(@RequestBody ChatRequest request,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        log.info("收到聊天请求：query={}, sessionId={}, tenantId={}", 
                request.getQuery(), request.getSessionId(), tenantId);
        
        // 如果请求体中没有设置 tenantId，从请求头获取
        if (request.getTenantId() == null && tenantId != null) {
            request.setTenantId(tenantId);
        }
        
        // 从 SecurityContext 获取当前用户 ID
        if (request.getUserId() == null) {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof SecurityUser) {
                request.setUserId(((SecurityUser) principal).getUserId());
            }
        }
        
        // 使用 RagAgentService 处理（Agent 模式，LLM 自动决定调用工具）
        AgentResult result = ragAgentService.process(request.getQuery());
        
        // 保存会话和聊天记录（包含自动重命名逻辑）
        if (request.getSessionId() != null) {
            ragSessionService.saveConversation(
                    request.getTenantId() != null ? request.getTenantId() : 1L,
                    request.getSessionId(),
                    request.getUserId(),
                    request.getQuery(),
                    result.getAnswer(),
                    result.getToolContext(),
                    null, null, null
            );
        }
        
        ChatResponse response = ChatResponse.builder()
                .answer(result.getAnswer())
                .build();
        
        log.info("聊天响应完成：answerLength={}, toolContext={}", 
                response.getAnswer() != null ? response.getAnswer().length() : 0,
                result.getToolContext());
        
        return R.ok(response);
    }
    
    /**
     * 保留独立 RAG 入口（标记为 Deprecated，供现有前端使用）
     * 
     * @param query RAG 查询
     * @param tenantId 租户 ID
     * @return RAG 结果
     */
    @PostMapping("/rag/search")
    @PreAuthorize("isAuthenticated()")
    @Deprecated
    public R<RagResult> ragSearch(@RequestBody RagQuery query,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        log.info("收到 RAG 检索请求：query={}, tenantId={}", query.getQuery(), tenantId);
        
        // 如果请求体中没有设置 tenantId，从请求头获取
        if (query.getTenantId() == null && tenantId != null) {
            query.setTenantId(tenantId);
        }
        
        RagResult result = ragSearchService.search(query);
        
        return R.ok(result);
    }
}
