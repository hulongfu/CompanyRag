package com.company.rag.web.controller;

import com.company.rag.agent.service.AgentResult;
import com.company.rag.agent.service.RagAgentService;
import com.company.rag.common.model.R;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.service.RagSearchService;
import lombok.Data;
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
    
    private final RagAgentService ragAgentService;
    private final RagSearchService ragSearchService;
    
    /**
     * 统一对话入口（Agent 编排，LLM 决定调用工具）
     * 
     * @param request 聊天请求
     * @return 聊天响应
     */
    @PostMapping("/chat")
    public R<String> chat(@RequestBody ChatRequest request) {
        log.info("收到聊天请求：message={}", request.getMessage());
        
        AgentResult result = ragAgentService.process(request.getMessage());
        
        return R.ok(result.getAnswer());
    }
    
    /**
     * 保留独立 RAG 入口（标记为 Deprecated，供现有前端使用）
     * 
     * @param query RAG 查询
     * @param userId 用户 ID（从请求头获取）
     * @return RAG 结果
     */
    @PostMapping("/rag/search")
    @Deprecated
    public R<RagResult> ragSearch(@RequestBody RagQuery query,
                                   @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId) {
        log.info("收到 RAG 检索请求：query={}", query.getQuery());
        
        query.setUserId(userId);
        RagResult result = ragSearchService.search(query);
        
        return R.ok(result);
    }
    
    /**
     * 聊天请求
     */
    @Data
    public static class ChatRequest {
        /**
         * 用户消息
         */
        private String message;
    }
}
