package com.company.rag.mcp.filter;

import com.company.rag.common.constant.RagConstant;
import com.company.rag.common.security.JwtTokenProvider;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.service.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * MCP 安全过滤器
 * 
 * 职责：
 * 1. 从请求头解析 JWT Token（可选，支持匿名访问）
 * 2. 验证 Token 有效性并提取用户信息
 * 3. 设置租户上下文（X-Tenant-Id 请求头）
 * 4. 验证用户是否属于指定租户
 * 
 * 注意：MCP Server 默认支持匿名访问（用于内部服务调用），
 * 但建议在生产环境中启用 JWT 认证。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpSecurityFilter extends OncePerRequestFilter {
    
    private final JwtTokenProvider jwtTokenProvider;
    private final TenantService tenantService;
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                   HttpServletResponse response, 
                                   FilterChain filterChain) throws ServletException, IOException {
        
        String path = request.getRequestURI();
        
        // 仅拦截 MCP 端点
        if (!path.startsWith("/mcp")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        try {
            // 1. 从请求头获取 JWT Token
            String token = extractTokenFromHeader(request);
            
            if (token != null && !token.isEmpty()) {
                // 2. 验证 Token 有效性
                if (jwtTokenProvider.validateToken(token)) {
                    // 3. 提取用户信息
                    Long userId = jwtTokenProvider.getUserIdFromToken(token);
                    String username = jwtTokenProvider.getUsernameFromToken(token);
                    List<Long> tenantIds = jwtTokenProvider.getTenantIdsFromToken(token);
                    
                    log.debug("MCP 请求认证成功：userId={}, username={}, tenantIds={}", 
                            userId, username, tenantIds);
                    
                    // 4. 设置用户上下文
                    TenantContext.setUserId(userId);
                    
                    // 5. 处理租户上下文
                    String tenantIdHeader = request.getHeader(RagConstant.HEADER_TENANT_ID);
                    if (tenantIdHeader != null) {
                        try {
                            Long requestedTenantId = Long.valueOf(tenantIdHeader);
                            
                            // 6. 验证用户是否属于该租户
                            if (tenantIds != null && tenantIds.contains(requestedTenantId)) {
                                TenantContext.setTenantId(requestedTenantId);
                                log.debug("MCP 租户验证成功：userId={}, tenantId={}", userId, requestedTenantId);
                            } else {
                                log.warn("MCP 租户验证失败：userId={} 不属于 tenantId={}", userId, requestedTenantId);
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                                        "用户无权访问该租户");
                                return;
                            }
                        } catch (NumberFormatException e) {
                            log.warn("MCP 无效的租户 ID 格式：{}", tenantIdHeader);
                        }
                    } else if (tenantIds != null && !tenantIds.isEmpty()) {
                        // 如果没有指定租户 ID，使用用户的第一个租户
                        TenantContext.setTenantId(tenantIds.get(0));
                        log.debug("MCP 使用默认租户：userId={}, defaultTenantId={}", userId, tenantIds.get(0));
                    }
                } else {
                    log.warn("MCP Token 无效");
                    // Token 无效时不强制拦截，允许匿名访问（用于内部服务调用）
                }
            } else {
                log.debug("MCP 匿名访问（无 Token）");
                // 匿名访问，不设置用户上下文
            }
            
            // 继续执行过滤器链
            filterChain.doFilter(request, response);
            
        } finally {
            // 清理上下文（在请求完成后）
            // 注意：TenantContext 的清理由 TenantInterceptor 负责
        }
    }
    
    /**
     * 从请求头提取 JWT Token
     */
    private String extractTokenFromHeader(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
