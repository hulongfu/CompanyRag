package com.company.rag.web.controller;

import com.company.rag.agent.service.AgentResult;
import com.company.rag.agent.service.RagAgentService;
import com.company.rag.common.model.R;
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
    
    /**
     * 统一对话入口（Agent 编排，LLM 决定调用工具）
     * 
     * @param request 聊天请求
     * @return 聊天响应
     */
    @PostMapping("/chat")
    public R<AgentResult> chat(@RequestBody ChatRequest request) {
        log.info("收到聊天请求：message={}", request.getMessage());
        
        AgentResult result = ragAgentService.process(request.getMessage());
        
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
