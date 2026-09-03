package com.company.rag.web.controller;

import com.company.rag.agent.service.AgentResult;
import com.company.rag.agent.service.RagAgentService;
import com.company.rag.common.model.R;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.response.ChatRequest;
import com.company.rag.rag.response.ChatResponse;
import com.company.rag.rag.service.RagSearchService;
import com.company.rag.rag.service.RagSessionService;
import com.company.rag.tenant.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

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
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        log.info("收到聊天请求：query={}, sessionId={}, headerTenantId={}", 
                request.getQuery(), request.getSessionId(), headerTenantId);
        
        // 1. 设置租户和会话上下文（用于工具调用时获取）
        TenantContext.setSessionId(request.getSessionId());
        
        try {
            // 【安全关键】必须使用请求头中的租户 ID（已经过 JwtAuthenticationFilter 验证）
            // 请求体中的 tenantId 是客户端可控的，完全不可信任，直接忽略
            Long verifiedTenantId = headerTenantId;
            
            // 【关键校验】租户 ID 必须存在，这是多租户隔离的底线
            if (verifiedTenantId == null) {
                log.error("租户 ID 缺失，拒绝服务：query={}", request.getQuery());
                throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
            }
            
            // 将已验证的租户 ID 设置到请求对象中（供后续使用）
            request.setTenantId(verifiedTenantId);
            TenantContext.setTenantId(verifiedTenantId);
            
            // 【安全关键】用户 ID 必须从已认证的安全上下文中获取，不能信任请求体
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            Long verifiedUserId = null;
            if (principal instanceof SecurityUser) {
                verifiedUserId = ((SecurityUser) principal).getUserId();
            }
            
            // 【关键校验】用户 ID 必须存在，这是审计追踪的底线
            if (verifiedUserId == null) {
                log.error("用户 ID 缺失，拒绝服务：principal={}, tenantId={}", 
                        principal != null ? principal.getClass().getSimpleName() : "null", 
                        verifiedTenantId);
                throw new IllegalStateException("用户 ID 不能为空，请确认用户已正确登录");
            }
            
            // 将已验证的用户 ID 设置到请求对象中（供后续使用）
            request.setUserId(verifiedUserId);
            TenantContext.setUserId(verifiedUserId);
            
            // 使用 RagAgentService 处理（Agent 模式，LLM 自动决定调用工具）
            // 如果有 sessionId 和 tenantId，读取历史会话记录并传入
            AgentResult result;
            if (request.getSessionId() != null && request.getTenantId() != null) {
                // 读取历史会话（按时间升序）
                List<RagSession> historySessions = ragSessionService.getSessionDetail(
                        request.getTenantId(), verifiedUserId, request.getSessionId());
                
                // 转换为 Message 列表
                List<Message> historyMessages = new ArrayList<>();
                for (RagSession session : historySessions) {
                    historyMessages.add(new UserMessage(session.getQuery()));
                    historyMessages.add(new AssistantMessage(session.getAnswer()));
                }
                
                log.debug("加载会话历史：sessionId={}, historySize={}", 
                        request.getSessionId(), historyMessages.size() / 2);
                
                // 调用带历史的处理方法
                result = ragAgentService.processWithHistory(historyMessages, request.getQuery());
            } else {
                // 无 sessionId 或 tenantId 缺失，使用无历史模式
                // 注意：tenantId 缺失时不读取历史，避免"读不到旧记忆却存到租户 1"的割裂
                result = ragAgentService.process(request.getQuery());
            }
            
            // 保存会话和聊天记录（包含自动重命名逻辑）
            // 如果有 sessionId，无论 tenantId 是否为空都保存（为空时使用默认租户 1）
            if (request.getSessionId() != null) {
                ragSessionService.saveConversation(
                        request.getTenantId(),
                        request.getSessionId(),
                        request.getUserId(),
                        request.getQuery(),
                        result.getAnswer(),
                        result.getToolContext(),
                        null, null, null
                );
                log.debug("保存会话记录：sessionId={}, tenantId={}, userId={}", 
                        request.getSessionId(), request.getTenantId(), request.getUserId());
            }
            
            ChatResponse response = ChatResponse.builder()
                    .answer(result.getAnswer())
                    .build();
            
            log.info("聊天响应完成：answerLength={}, toolContext={}", 
                    response.getAnswer() != null ? response.getAnswer().length() : 0,
                    result.getToolContext());
            
            return R.ok(response);
            
        } finally {
            // 2. 清理上下文（防止内存泄漏）
            TenantContext.clear();
        }
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
                                   @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        log.info("收到 RAG 检索请求：query={}, headerTenantId={}", query.getQuery(), headerTenantId);
        
        // 【安全关键】必须使用请求头中的租户 ID（已经过 JwtAuthenticationFilter 验证）
        // 请求体中的 tenantId 是客户端可控的，完全不可信任，直接忽略
        if (headerTenantId == null) {
            log.error("租户 ID 缺失，拒绝服务：query={}", query.getQuery());
            throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
        }
        
        // 将已验证的租户 ID 设置到请求对象中（供后续使用）
        query.setTenantId(headerTenantId);
        
        RagResult result = ragSearchService.search(query);
        
        return R.ok(result);
    }
}
