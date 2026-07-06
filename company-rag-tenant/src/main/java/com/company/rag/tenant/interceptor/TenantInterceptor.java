package com.company.rag.tenant.interceptor;

import com.company.rag.common.constant.RagConstant;
import com.company.rag.tenant.context.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户拦截器 - 从请求头解析租户信息并注入上下文
 */
public class TenantInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenantId = request.getHeader(RagConstant.HEADER_TENANT_ID);
        String userId = request.getHeader(RagConstant.HEADER_USER_ID);

        if (tenantId != null) {
            TenantContext.setTenantId(Long.valueOf(tenantId));
        }
        if (userId != null) {
            TenantContext.setUserId(Long.valueOf(userId));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
    }
}
