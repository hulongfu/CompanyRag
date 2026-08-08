package com.company.rag.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

/**
 * SPA 兜底控制器：将所有非 API、非静态资源路径返回 index 模板
 * 
 * 路径匹配规则：
 * - 使用正则 {path:[^\\.]*} 只匹配不包含点号的路径（避免拦截 .js/.css 等静态资源）
 * - 显式排除 API、Swagger、WebJars、Actuator 等路径，让对应的处理器处理
 */
@Controller
public class SpaFallbackController {

    @GetMapping("/{path:[^\\\\.]*}")
    public String fallback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        
        // 排除 API 路径
        if (path.startsWith("/api/")) {
            return null;
        }
        
        // 排除 Swagger UI 和 API 文档路径
        if (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) {
            return null;
        }
        
        // 排除 WebJars 静态资源
        if (path.startsWith("/webjars/")) {
            return null;
        }
        
        // 排除 Actuator 监控端点
        if (path.startsWith("/actuator/")) {
            return null;
        }
        
        // 排除 favicon 和 error 路径
        if (path.equals("/favicon.ico") || path.equals("/error")) {
            return null;
        }
        
        // 所有其他路径返回 index 模板（SPA 兜底）
        return "index";
    }
}