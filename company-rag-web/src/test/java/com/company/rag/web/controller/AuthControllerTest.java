package com.company.rag.web.controller;

import com.company.rag.common.model.AuditLogContext;
import com.company.rag.common.model.R;
import com.company.rag.common.security.JwtTokenProvider;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.common.service.AuditLogService;
import com.company.rag.tenant.service.TenantService;
import com.company.rag.web.model.AuthRequest;
import com.company.rag.web.model.AuthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthController 单元测试
 *
 * 覆盖 login/logout 走 AuditLogService.record（方案 A，同步）的归属采集。
 * 纯 Mockito：手动按 AuthController 的 @RequiredArgsConstructor 构造，避开 Spring Security 上下文。
 */
class AuthControllerTest {

    private AuthenticationManager authenticationManager;
    private JwtTokenProvider jwtTokenProvider;
    private com.company.rag.common.security.JwtProperties jwtProperties;
    private TenantService tenantService;
    private AuditLogService auditLogService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        jwtProperties = mock(com.company.rag.common.security.JwtProperties.class);
        tenantService = mock(TenantService.class);
        auditLogService = mock(AuditLogService.class);
        // @RequiredArgsConstructor 生成的构造参数顺序：authenticationManager, jwtTokenProvider, jwtProperties, tenantService, auditLogService
        controller = new AuthController(authenticationManager, jwtTokenProvider, jwtProperties, tenantService, auditLogService);
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginRecordsSynchronousAuditWithOwnershipFromSecurityUser() {
        // 认证主体：SecurityUser(userId=7, tenantId=3, tenantIds=[3])
        SecurityUser securityUser = new SecurityUser(7L, 3L, List.of(3L), "alice", "pw", "admin", true);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(securityUser);
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any())).thenReturn("access-token");
        when(jwtTokenProvider.generateRefreshToken(any())).thenReturn("refresh-token");
        when(jwtProperties.getAccessTokenExpiration()).thenReturn(7200000L);

        AuthRequest request = new AuthRequest();
        request.setUsername("alice");
        request.setPassword("pw");
        R<AuthResponse> result = controller.login(request);

        // 返回成功
        assertEquals(200, result.getCode());

        // 捕获 record 的 AuditLogContext
        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).record(captor.capture());
        AuditLogContext ctx = captor.getValue();
        assertEquals("LOGIN", ctx.getActionType());
        assertEquals("USER", ctx.getTargetType());
        assertEquals("7", ctx.getTargetId());
        assertEquals(7L, ctx.getUserId());
        assertEquals("3", ctx.getTenantId());
        assertTrue(ctx.getDetail().contains("alice"));
    }

    @Test
    void logoutRecordsSynchronousAuditWithOwnership() {
        SecurityUser securityUser = new SecurityUser(9L, 5L, List.of(5L), "bob", "pw", "admin", true);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(securityUser);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        controller.logout(null);

        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).record(captor.capture());
        AuditLogContext ctx = captor.getValue();
        assertEquals("LOGOUT", ctx.getActionType());
        assertEquals("USER", ctx.getTargetType());
        assertEquals("9", ctx.getTargetId());
        assertEquals(9L, ctx.getUserId());
        assertEquals("5", ctx.getTenantId());
    }

    @Test
    void loginFailureReturns401AndDoesNotAudit() {
        // 认证失败：authenticate 抛异常 → 401。
        // 登录失败不记审计：无 SecurityUser 取不到 tenant_id，audit_log.tenant_id NOT NULL，
        // 记了也会因约束违规被 try/catch 吞掉而丢失。
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new RuntimeException("bad credentials"));

        AuthRequest request = new AuthRequest();
        request.setUsername("mallory");
        request.setPassword("wrong");
        R<AuthResponse> result = controller.login(request);

        assertEquals(401, result.getCode());
        // 验证未调用审计记录（LOGIN_FAILED 已移除）
        verify(auditLogService, org.mockito.Mockito.never()).record(any());
    }
}