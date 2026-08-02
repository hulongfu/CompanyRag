package com.company.rag.common.aspect;

import com.company.rag.common.annotation.AuditLog;
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

            // 记录审计日志
            auditLogService.recordAuditLog(
                    auditLog.actionType(),
                    auditLog.targetType(),
                    targetId,
                    detail
            );
        } catch (Exception e) {
            log.warn("审计日志记录失败：{}", e.getMessage());
        }

        return result;
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
