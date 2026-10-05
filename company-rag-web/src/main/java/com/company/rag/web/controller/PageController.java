package com.company.rag.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端页面路由 - 前后端一体化
 */
@Controller
public class PageController {

    @GetMapping({"/", "/index", "/chat"})
    public String index() {
        return "index";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/documents")
    public String documents() {
        return "documents";
    }

    @GetMapping("/admin")
    public String admin() {
        return "admin";
    }

    /** 回答评估中心页面 */
    @GetMapping("/eval")
    public String eval() {
        return "eval";
    }

    /** 工具审批面板页面 */
    @GetMapping("/tool-approval")
    public String toolApproval() {
        return "tool-approval";
    }

    /** MCP 服务器状态管理页面 */
    @GetMapping("/mcp-status")
    public String mcpStatus() {
        return "mcp-status";
    }

    @GetMapping("/audit-log.html")
    public String auditLog() {
        return "audit-log";
    }
}
