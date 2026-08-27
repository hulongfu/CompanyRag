package com.company.rag.mcp.filter;

import com.company.rag.common.constant.RagConstant;
import com.company.rag.common.security.JwtTokenProvider;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.model.Tenant;
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
 * 1. 从请求头解析 JWT Token（强制，禁止匿名访问）
 * 2. 验证 Token 有效性并提取用户信息
 * 3. 设置租户上下文（X-Tenant-Id 请求头）
 * 4. 验证用户是否属于指定租户
 * 5. 查询租户数据库设置 Schema（自给自足，不依赖其他 Filter）
 * 6. 在 finally 块中清理租户上下文，防止线程池复用导致的数据污染
 * 
 * 安全策略：
 * - 禁止匿名访问：所有 MCP 请求必须提供有效的 JWT Token
 * - 强制租户隔离：必须设置租户上下文才能访问后续业务逻辑
 * - 防御性检查：在调用知识库工具前验证租户上下文已正确设置
 * - 自给自足：独立查询 TenantService 设置 Schema，不依赖 JwtAuthenticationFilter
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
            
            // 2. Token 不存在时，拒绝访问（禁止匿名访问）
            if (token == null || token.isEmpty()) {
                log.warn("MCP 拒绝匿名访问：URI={}", path);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, 
                        "MCP 访问需要 JWT 认证，请提供 Authorization 请求头");
                return;
            }
            
            // 3. 验证 Token 有效性
            if (!jwtTokenProvider.validateToken(token)) {
                log.warn("MCP Token 无效：URI={}", path);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, 
                        "无效的 JWT Token");
                return;
            }
            
            // 4. 提取用户信息
            Long userId = jwtTokenProvider.getUserIdFromToken(token);
            String username = jwtTokenProvider.getUsernameFromToken(token);
            List<Long> tenantIds = jwtTokenProvider.getTenantIdsFromToken(token);
            
            log.debug("MCP 请求认证成功：userId={}, username={}, tenantIds={}", 
                    userId, username, tenantIds);
            
            // 5. 设置用户上下文
            TenantContext.setUserId(userId);
            
            // 6. 处理租户上下文
            String tenantIdHeader = request.getHeader(RagConstant.HEADER_TENANT_ID);
            if (tenantIdHeader != null) {
                try {
                    Long requestedTenantId = Long.valueOf(tenantIdHeader);
                    
                    // 7. 验证用户是否属于该租户
                    if (tenantIds != null && tenantIds.contains(requestedTenantId)) {
                        TenantContext.setTenantId(requestedTenantId);
                        
                        // 8. 设置租户 Schema（用于 DatabaseQueryTool 等原生 JDBC 操作）
                        Tenant tenant = tenantService.getById(requestedTenantId);
                        if (tenant != null && tenant.getSchemaName() != null) {
                            TenantContext.setSchema(tenant.getSchemaName());
                            log.info("MCP 设置租户 Schema：userId={}, tenantId={}, schema={}", 
                                    userId, requestedTenantId, tenant.getSchemaName());
                        } else {
                            log.warn("MCP 租户 Schema 为空：userId={}, tenantId={}", userId, requestedTenantId);
                        }
                        
                        log.info("MCP 租户验证成功：userId={}, tenantId={}", userId, requestedTenantId);
                    } else {
                        log.warn("MCP 租户验证失败：userId={} 不属于 tenantId={}", userId, requestedTenantId);
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                                "用户无权访问该租户");
                        return;
                    }
                } catch (NumberFormatException e) {
                    log.warn("MCP 无效的租户 ID 格式：{}", tenantIdHeader);
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, 
                            "无效的租户 ID 格式");
                    return;
                }
            } else if (tenantIds != null && !tenantIds.isEmpty()) {
                // 如果没有指定租户 ID，使用用户的第一个租户
                Long defaultTenantId = tenantIds.get(0);
                TenantContext.setTenantId(defaultTenantId);
                
                // 设置默认租户的 Schema
                Tenant tenant = tenantService.getById(defaultTenantId);
                if (tenant != null && tenant.getSchemaName() != null) {
                    TenantContext.setSchema(tenant.getSchemaName());
                    log.info("MCP 设置默认租户 Schema：userId={}, tenantId={}, schema={}", 
                            userId, defaultTenantId, tenant.getSchemaName());
                }
                
                log.info("MCP 使用默认租户：userId={}, defaultTenantId={}", userId, defaultTenantId);
            } else {
                // 用户没有任何租户，拒绝访问
                log.warn("MCP 用户无租户：userId={}", userId);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                        "用户未关联任何租户");
                return;
            }
            
            // 8. 验证租户上下文是否已设置（防御性检查）
            if (TenantContext.getTenantId() == null) {
                log.error("MCP 租户上下文未设置，拒绝访问：userId={}", userId);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, 
                        "租户上下文未设置");
                return;
            }
            
            // 继续执行过滤器链
            filterChain.doFilter(request, response);
            
        } catch (Exception e) {
            log.error("MCP 请求处理失败：URI={}", path, e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, 
                    "MCP 请求处理失败：" + e.getMessage());
        } finally {
            // 清理上下文（在请求完成后）
            TenantContext.clear();
            log.debug("MCP 请求完成，已清理租户上下文：URI={}", path);
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
