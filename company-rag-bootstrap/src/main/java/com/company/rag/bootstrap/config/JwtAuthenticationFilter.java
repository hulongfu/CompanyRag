package com.company.rag.bootstrap.config;

import com.company.rag.common.security.JwtTokenProvider;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.model.Tenant;
import com.company.rag.tenant.service.TenantService;
import jakarta.servlet.ServletException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final TenantService tenantService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        try {
            String token = extractToken(request);

            if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
                try {
                    Long userId = jwtTokenProvider.getUserIdFromToken(token);
                    String username = jwtTokenProvider.getUsernameFromToken(token);
                    List<Long> tenantIds = jwtTokenProvider.getTenantIdsFromToken(token);
                    String role = jwtTokenProvider.getRoleFromToken(token);

                    // 从请求头获取当前租户 ID
                    String tenantHeader = request.getHeader("X-Tenant-Id");
                    Long currentTenantId = null;
                    if (StringUtils.hasText(tenantHeader)) {
                        try {
                            currentTenantId = Long.valueOf(tenantHeader);
                            // 验证 X-Tenant-Id 是否在 token 的 tenantIds 中
                            if (tenantIds == null || !tenantIds.contains(currentTenantId)) {
                                log.warn("租户 ID 不在可访问列表中：userId={}, tenantId={}", userId, currentTenantId);
                                SecurityContextHolder.clearContext();
                                filterChain.doFilter(request, response);
                                return;
                            }
                        } catch (NumberFormatException e) {
                            log.warn("无效的 X-Tenant-Id：{}", tenantHeader);
                        }
                    }

                    // 如果没有 X-Tenant-Id，使用默认租户（tenantIds 第一个）
                    if (currentTenantId == null && tenantIds != null && !tenantIds.isEmpty()) {
                        currentTenantId = tenantIds.get(0);
                    }

                    SecurityUser securityUser = new SecurityUser(
                            userId, currentTenantId, tenantIds, username, "", role, true
                    );

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    securityUser, null, securityUser.getAuthorities()
                            );

                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    if (currentTenantId != null) {
                        TenantContext.setTenantId(currentTenantId);
                        TenantContext.setUserId(userId);
                        // 设置租户 schema（用于 DatabaseQueryTool 等原生 JDBC 操作）
                        Tenant currentTenant = tenantService.getById(currentTenantId);
                        if (currentTenant != null && currentTenant.getSchemaName() != null) {
                            TenantContext.setSchema(currentTenant.getSchemaName());
                            log.debug("设置租户 Schema：userId={}, schema={}", userId, currentTenant.getSchemaName());
                        }
                    }

                    log.debug("JWT 认证成功：userId={}, currentTenantId={}, role={}", userId, currentTenantId, role);
                } catch (Exception e) {
                    log.warn("JWT 认证处理异常：{}", e.getMessage());
                    // 不清除 SecurityContext，让后续的@PreAuthorize 处理权限拒绝
                    // SecurityContextHolder.clearContext();
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            // 确保请求结束时清理 ThreadLocal，防止线程池复用导致上下文污染
            // 注意：TenantInterceptor.afterCompletion() 也会清理，但 Filter 可能拦截在 MVC 之前
            TenantContext.clear();
        }
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
