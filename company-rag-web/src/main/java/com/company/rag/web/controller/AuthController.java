package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.common.security.JwtProperties;
import com.company.rag.common.security.JwtTokenProvider;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.tenant.service.TenantService;
import com.company.rag.web.model.AuthRequest;
import com.company.rag.web.model.AuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 认证控制器
 * 
 * 提供登录、刷新令牌、登出功能
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final TenantService tenantService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    /**
     * 用户登录
     * 
     * @param request 登录请求（用户名、密码）
     * @return 认证响应（Access Token、Refresh Token、用户信息）
     */
    @PostMapping("/login")
    public R<AuthResponse> login(@RequestBody AuthRequest request) {
        log.info("用户登录：{}", request.getUsername());

        try {
            // 1. 认证用户名密码
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername(),
                            request.getPassword()
                    )
            );

            // 2. 获取用户信息
            SecurityUser securityUser = (SecurityUser) authentication.getPrincipal();
            Long userId = securityUser.getUserId();
            List<Long> tenantIds = securityUser.getTenantIds();
            Long currentTenantId = tenantIds != null && !tenantIds.isEmpty() ? tenantIds.get(0) : null;
            String role = securityUser.getRole();

            // 3. 生成 Access Token 和 Refresh Token
            String accessToken = jwtTokenProvider.generateAccessToken(userId, securityUser.getUsername(), tenantIds, role);
            String refreshToken = jwtTokenProvider.generateRefreshToken(userId);

            // 4. 记录审计日志
            tenantService.recordAuditLog("LOGIN", "USER", String.valueOf(userId), 
                    "用户登录成功：" + request.getUsername());

            log.info("用户登录成功：{}, userId={}, tenantIds={}, currentTenantId={}, role={}", 
                    request.getUsername(), userId, tenantIds, currentTenantId, role);

            // 5. 返回认证响应
            AuthResponse response = AuthResponse.builder()
                    .token(accessToken)
                    .refreshToken(refreshToken)
                    .expireIn(jwtProperties.getAccessTokenExpiration())
                    .userId(userId)
                    .tenantIds(tenantIds)
                    .currentTenantId(currentTenantId)
                    .role(role)
                    .displayName(securityUser.getUsername())
                    .build();

            return R.ok(response);

        } catch (Exception e) {
            log.warn("用户登录失败：{}, 原因：{}", request.getUsername(), e.getMessage());
            return R.fail(401, "用户名或密码错误");
        }
    }

    /**
     * 刷新令牌
     * 
     * @param request 刷新令牌请求
     * @return 新的 Access Token
     */
    @PostMapping("/refresh")
    public R<AuthResponse> refreshToken(@RequestBody RefreshTokenRequest request) {
        log.info("刷新令牌");

        try {
            String refreshToken = request.getRefreshToken();

            // 1. 验证 Refresh Token
            if (!jwtTokenProvider.validateToken(refreshToken)) {
                log.warn("Refresh Token 无效");
                return R.fail(401, "Refresh Token 无效或已过期");
            }

            // 2. 从 Refresh Token 中获取用户 ID
            Long userId = jwtTokenProvider.getUserIdFromToken(refreshToken);

            // 3. 加载用户信息
            SecurityUser securityUser = tenantService.loadSecurityUserById(userId);
            if (securityUser == null) {
                log.warn("用户不存在：{}", userId);
                return R.fail(404, "用户不存在");
            }

            // 4. 获取租户列表和当前租户
            List<Long> tenantIds = securityUser.getTenantIds();
            Long currentTenantId = tenantIds != null && !tenantIds.isEmpty() ? tenantIds.get(0) : null;

            // 5. 生成新的 Access Token
            String newAccessToken = jwtTokenProvider.generateAccessToken(
                    securityUser.getUserId(),
                    securityUser.getUsername(),
                    tenantIds,
                    securityUser.getRole()
            );

            log.info("刷新令牌成功：userId={}", userId);

            // 6. 返回新的认证响应
            AuthResponse response = AuthResponse.builder()
                    .token(newAccessToken)
                    .refreshToken(refreshToken)  // 返回原来的 Refresh Token
                    .expireIn(jwtProperties.getAccessTokenExpiration())
                    .userId(securityUser.getUserId())
                    .tenantIds(tenantIds)
                    .currentTenantId(currentTenantId)
                    .role(securityUser.getRole())
                    .displayName(securityUser.getUsername())
                    .build();

            return R.ok(response);

        } catch (Exception e) {
            log.warn("刷新令牌失败：{}", e.getMessage());
            return R.fail(401, "刷新令牌失败：" + e.getMessage());
        }
    }

    /**
     * 用户登出
     * 
     * @param request HTTP 请求
     * @return 登出结果
     */
    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        try {
            // 1. 获取当前认证信息
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            
            if (authentication != null && authentication.getPrincipal() instanceof SecurityUser) {
                SecurityUser securityUser = (SecurityUser) authentication.getPrincipal();
                Long userId = securityUser.getUserId();

                // 2. 记录审计日志
                tenantService.recordAuditLog("LOGOUT", "USER", String.valueOf(userId),
                        "用户登出：" + securityUser.getUsername());

                log.info("用户登出：{}, userId={}", securityUser.getUsername(), userId);
            }

            // 3. 清除 SecurityContext
            SecurityContextHolder.clearContext();

            // 4. 清除 Session（如果有）
            if (request.getSession(false) != null) {
                request.getSession().invalidate();
            }

            return R.ok();

        } catch (Exception e) {
            log.warn("用户登出失败：{}", e.getMessage());
            return R.fail(500, "登出失败：" + e.getMessage());
        }
    }

    /**
     * 刷新令牌请求体
     */
    @lombok.Data
    public static class RefreshTokenRequest {
        private String refreshToken;
    }
}
