package com.company.rag.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

@Controller
public class SpaFallbackController {

    @GetMapping("/**")
    public String fallback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        // 排除 API 路径和静态资源（静态资源已由 /static/** 处理，这里不会再进入，但保留以防万一）
        if (path.startsWith("/api/") || path.startsWith("/static/") || path.equals("/favicon.ico")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
        // 所有其他路径（包括 /、/home、/user/profile 等）返回 index 模板
        return "index";
    }
}