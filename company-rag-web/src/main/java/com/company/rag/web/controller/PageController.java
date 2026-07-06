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
}
