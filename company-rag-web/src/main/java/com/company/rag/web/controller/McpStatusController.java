package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.mcp.client.McpClientRegistry;
import com.company.rag.mcp.client.McpEndpointStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP 端点状态查询（仅平台管理员 ROLE_ADMIN 可访问）。
 */
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpStatusController {

    private final McpClientRegistry mcpClientRegistry;

    @GetMapping("/status")
    @PreAuthorize("hasRole('ADMIN')")
    public R<List<McpEndpointStatus>> status() {
        return R.ok(mcpClientRegistry.statusSnapshots());
    }
}