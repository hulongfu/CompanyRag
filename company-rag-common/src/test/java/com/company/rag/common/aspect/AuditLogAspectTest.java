package com.company.rag.common.aspect;

import com.company.rag.common.annotation.AuditLog;
import com.company.rag.common.model.AuditLogContext;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.common.service.AuditLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuditLogAspect 单测：归属采集、async 分发、ip 采集、user 为 null 跳过、失败降级
 */
@ExtendWith(MockitoExtension.class)
class AuditLogAspectTest {

    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ProceedingJoinPoint joinPoint;

    private AuditLogAspect aspect;
    private SecurityUser user;

    // ---- 测试方法（注解反射读取用）----
    @AuditLog(actionType = "DELETE_DOCUMENT", targetType = "document", targetId = "#arg0", detail = "删除文档")
    public void syncMethod(Long docId) {
    }

    @AuditLog(actionType = "EXECUTE_TOOL", targetType = "tool", targetId = "#arg0", detail = "执行工具", async = true)
    public void asyncMethod(String toolId) {
    }

    @AuditLog(actionType = "CLEAR_CACHE", targetType = "cache")
    public void noSpelMethod() {
    }

    @AuditLog(actionType = "DELETE_DOCUMENT", targetType = "document",
            targetId = "#docId", detail = "'删除文档：' + #docId")
    public void namedParamMethod(Long docId) {
    }

    @BeforeEach
    void setUp() throws Throwable {
        aspect = new AuditLogAspect(auditLogService);
        user = new SecurityUser(7L, 3L, List.of(3L), "admin", "", "admin", true);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        // 默认 mock 执行返回 null
        when(joinPoint.proceed()).thenReturn(null);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private Method mount(Method method, Object... args) throws Throwable {
        MethodSignature signature = mock(MethodSignature.class);
        lenient().when(signature.getName()).thenReturn(method.getName());
        lenient().when(signature.getParameterNames()).thenReturn(
                java.util.Arrays.stream(method.getParameters())
                        .map(java.lang.reflect.Parameter::getName).toArray(String[]::new));
        lenient().when(joinPoint.getSignature()).thenReturn(signature);
        lenient().when(joinPoint.getArgs()).thenReturn(args);
        return method;
    }

    @Test
    void collectsOwnershipAndAttributes() throws Throwable {
        Method m = mount(AuditLogAspectTest.class.getMethod("syncMethod", Long.class), 42L);
        AuditLog annotation = m.getAnnotation(AuditLog.class);

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).record(captor.capture());
        AuditLogContext ctx = captor.getValue();
        assertEquals("DELETE_DOCUMENT", ctx.getActionType());
        assertEquals("document", ctx.getTargetType());
        assertEquals("42", ctx.getTargetId());
        assertEquals("删除文档", ctx.getDetail());
        assertEquals(7L, ctx.getUserId());
        assertEquals("3", ctx.getTenantId());
    }

    @Test
    void dispatchAsyncWhenAnnotationIsAsync() throws Throwable {
        Method m = mount(AuditLogAspectTest.class.getMethod("asyncMethod", String.class), "tool-1");
        AuditLog annotation = m.getAnnotation(AuditLog.class);

        aspect.around(joinPoint, annotation);

        verify(auditLogService).recordAsync(any(AuditLogContext.class));
        verify(auditLogService, never()).record(any(AuditLogContext.class));
    }

    @Test
    void dispatchSyncByDefault() throws Throwable {
        Method m = mount(AuditLogAspectTest.class.getMethod("syncMethod", Long.class), 42L);
        AuditLog annotation = m.getAnnotation(AuditLog.class);

        aspect.around(joinPoint, annotation);

        verify(auditLogService).record(any(AuditLogContext.class));
        verify(auditLogService, never()).recordAsync(any(AuditLogContext.class));
    }

    @Test
    void resolveIpFromForwardedHeader() throws Throwable {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Method m = mount(AuditLogAspectTest.class.getMethod("noSpelMethod"));
        AuditLog annotation = m.getAnnotation(AuditLog.class);

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).record(captor.capture());
        assertEquals("203.0.113.5", captor.getValue().getIpAddress());
    }

    @Test
    void ipIsNullWhenNoRequest() throws Throwable {
        Method m = mount(AuditLogAspectTest.class.getMethod("noSpelMethod"));
        AuditLog annotation = m.getAnnotation(AuditLog.class);

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).record(captor.capture());
        assertNull(captor.getValue().getIpAddress());
    }

    @Test
    void skipsWhenUserNotSecurityUser() throws Throwable {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymous", null));
        Method m = mount(AuditLogAspectTest.class.getMethod("noSpelMethod"));
        AuditLog annotation = m.getAnnotation(AuditLog.class);

        aspect.around(joinPoint, annotation);

        verify(auditLogService, never()).record(any(AuditLogContext.class));
        verify(auditLogService, never()).recordAsync(any(AuditLogContext.class));
    }

    @Test
    void failureDoesNotLeakToMainFlow() throws Throwable {
        Method m = mount(AuditLogAspectTest.class.getMethod("noSpelMethod"));
        AuditLog annotation = m.getAnnotation(AuditLog.class);
        doThrow(new RuntimeException("db down")).when(auditLogService).record(any(AuditLogContext.class));

        Object result = aspect.around(joinPoint, annotation);

        assertEquals(null, result, "主方法结果应正常返回，无异常泄漏");
    }

    @Test
    void resolvesNamedParameterSpel() throws Throwable {
        // 用真实参数名（#docId）而非 #arg0，验证命名参数 SpEL 能解析
        Method m = mount(AuditLogAspectTest.class.getMethod("namedParamMethod", Long.class), 42L);
        AuditLog annotation = m.getAnnotation(AuditLog.class);

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<AuditLogContext> captor = ArgumentCaptor.forClass(AuditLogContext.class);
        verify(auditLogService).record(captor.capture());
        AuditLogContext ctx = captor.getValue();
        assertEquals("42", ctx.getTargetId());
        assertEquals("删除文档：42", ctx.getDetail());
    }
}