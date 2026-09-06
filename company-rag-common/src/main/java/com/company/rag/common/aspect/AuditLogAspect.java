package com.company.rag.common.aspect;

import com.company.rag.common.annotation.AuditLog;
import com.company.rag.common.model.AuditLogContext;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.common.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditLogService auditLogService;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint point, AuditLog auditLog) throws Throwable {
        Object result = point.proceed();

        try {
            // 获取当前用户
            SecurityUser user = getCurrentUser();
            if (user == null) {
                return result;
            }

            // 解析 SpEL 表达式
            String targetId = parseSpel(auditLog.targetId(), point);
            String detail = parseSpel(auditLog.detail(), point);

            // 归属采集（ip 在调用侧解析，异步线程取不到 RequestContextHolder）
            AuditLogContext ctx = AuditLogContext.builder()
                    .actionType(auditLog.actionType())
                    .targetType(auditLog.targetType())
                    .targetId(targetId)
                    .detail(detail)
                    .userId(user.getUserId())
                    .tenantId(user.getTenantId() != null ? String.valueOf(user.getTenantId()) : null)
                    .ipAddress(resolveIp())
                    .build();

            // 按 async 分发：数据类异步批量、管理类同步直写
            if (auditLog.async()) {
                auditLogService.recordAsync(ctx);
            } else {
                auditLogService.record(ctx);
            }
        } catch (Exception e) {
            log.warn("审计日志记录失败：{}", e.getMessage());
        }

        return result;
    }

    /**
     * 从请求头解析真实客户端 IP：X-Forwarded-For（取首个）→ X-Real-IP → remoteAddr；无请求返回 null。
     */
    private String resolveIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        HttpServletRequest request = attrs.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private SecurityUser getCurrentUser() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof SecurityUser) {
            return (SecurityUser) principal;
        }
        return null;
    }

    private String parseSpel(String expression, ProceedingJoinPoint point) {
        if (expression == null || expression.isEmpty()) {
            return "";
        }

        StandardEvaluationContext context = new StandardEvaluationContext();
        String[] paramNames = point.getSignature().getName().split("\\(");
        Object[] args = point.getArgs();

        for (int i = 0; i < args.length; i++) {
            context.setVariable("arg" + i, args[i]);
            if (paramNames.length > 0) {
                context.setVariable(paramNames[0], args[i]);
            }
        }

        try {
            Expression exp = parser.parseExpression(expression);
            Object value = exp.getValue(context);
            return value != null ? value.toString() : "";
        } catch (Exception e) {
            log.warn("SpEL 解析失败：{}", expression);
            return expression;
        }
    }
}
