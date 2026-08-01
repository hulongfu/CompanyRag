package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.rag.response.ChatRequest;
import com.company.rag.rag.response.ChatResponse;
import com.company.rag.rag.router.ChatRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 统一对话 Controller
 * 整合原有 AgentController 和 RagController
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {
    
    private final ChatRouter chatRouter;
    
    /**
     * 统一对话入口（使用 ChatRouter 统一路由处理）
     * 
     * @param request 聊天请求
     * @return 聊天响应
     */
    @PostMapping("/chat")
    public R<ChatResponse> chat(@RequestBody ChatRequest request,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long tenantId) {
        log.info("收到聊天请求：query={}, sessionId={}, tenantId={}", 
                request.getQuery(), request.getSessionId(), tenantId);
        
        // 如果请求体中没有设置 tenantId，从请求头获取
        if (request.getTenantId() == null && tenantId != null) {
            request.setTenantId(tenantId);
        }
        
        // 设置默认 userId
        if (request.getUserId() == null) {
            request.setUserId(1L);
        }
        
        // 使用 ChatRouter 统一处理，确保会话记录被保存
        ChatResponse response = chatRouter.route(request);
        
        log.info("聊天响应完成：answerLength={}, sources={}", 
                response.getAnswer() != null ? response.getAnswer().length() : 0,
                response.getSources() != null ? response.getSources().size() : 0);
        
        return R.ok(response);
    }
}
