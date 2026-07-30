package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.rag.response.ChatRequest;
import com.company.rag.rag.response.ChatResponse;
import com.company.rag.rag.router.ChatRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 聊天接口 Controller
 * 提供统一的聊天入口端点
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final ChatRouter chatRouter;

    /**
     * 聊天接口
     * 统一处理用户的聊天请求，通过 ChatRouter 路由到不同处理器
     *
     * @param request 聊天请求
     * @return 统一响应格式的聊天响应
     */
    @PostMapping("/chat")
    public R<ChatResponse> chat(@RequestBody ChatRequest request) {
        return R.ok(chatRouter.route(request));
    }
}
