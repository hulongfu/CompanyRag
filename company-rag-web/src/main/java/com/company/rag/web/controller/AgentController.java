package com.company.rag.web.controller;

import com.company.rag.agent.service.RagAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * @deprecated 使用 {@link ChatController} 替代，此类将在后续版本中删除
 */
@Deprecated
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final RagAgentService agentService;

    // 所有方法已移除，请使用 ChatController
}
