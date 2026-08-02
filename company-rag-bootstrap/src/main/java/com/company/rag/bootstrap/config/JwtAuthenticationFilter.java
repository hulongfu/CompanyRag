package com.company.rag.bootstrap.config;

import com.company.rag.common.security.JwtTokenProvider;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.tenant.context.TenantContext;
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

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            try {
                Long userId = jwtTokenProvider.getUserIdFromToken(token);
                Long tenantId = jwtTokenProvider.getTenantIdFromToken(token);
                String role = jwtTokenProvider.getRoleFromToken(token);

                SecurityUser securityUser = new SecurityUser(
                        userId, tenantId, "", "", role, true
                );

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                securityUser, null, securityUser.getAuthorities()
                        );

                SecurityContextHolder.getContext().setAuthentication(authentication);

                TenantContext.setTenantId(tenantId);
                TenantContext.setUserId(userId);

                log.debug("JWT 认证成功：userId={}, tenantId={}, role={}", userId, tenantId, role);
            } catch (Exception e) {
                log.warn("JWT 认证处理异常：{}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
