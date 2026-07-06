package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.agent.service.RagAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Agent工具调用接口
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final RagAgentService agentService;

    @PostMapping("/chat")
    public R<String> chat(@RequestBody Map<String, String> request) {
        String message = request.get("message");
        String context = request.get("context");
        return R.ok(agentService.process(message, context));
    }

    @PostMapping("/query-db")
    public R<String> queryDb(@RequestBody Map<String, String> request) {
        return R.ok(agentService.queryDatabase(request.get("sql")));
    }

    @PostMapping("/search-code")
    public R<String> searchCode(@RequestBody Map<String, String> request) {
        return R.ok(agentService.searchCode(
                request.get("keyword"),
                request.get("fileExtension")));
    }

    @GetMapping("/api-doc")
    public R<String> apiDoc(@RequestParam(required = false) String filter) {
        return R.ok(agentService.getApiDoc(filter));
    }
}
