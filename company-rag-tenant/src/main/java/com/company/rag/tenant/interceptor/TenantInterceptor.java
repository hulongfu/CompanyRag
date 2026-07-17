package com.company.rag.tenant.interceptor;

import com.company.rag.common.constant.RagConstant;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.context.TenantContextHelper;
import com.company.rag.tenant.mapper.TenantMapper;
import com.company.rag.tenant.model.Tenant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户拦截器 - 从请求头解析租户信息并注入上下文
 * 同时完成：
 * 1. ThreadLocal 上下文设置（TenantContext）
 * 2. PostgreSQL Schema 切换（TenantContextHelper）
 * 3. RLS session 变量设置
 */
@RequiredArgsConstructor
public class TenantInterceptor implements HandlerInterceptor {

    private final TenantContextHelper tenantContextHelper;
    private final TenantMapper tenantMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String tenantId = request.getHeader(RagConstant.HEADER_TENANT_ID);
        String userId = request.getHeader(RagConstant.HEADER_USER_ID);

        if (tenantId != null) {
            Long tid = Long.valueOf(tenantId);
            TenantContext.setTenantId(tid);

            // 从数据库获取租户的Schema名称，设置Schema隔离
            Tenant tenant = tenantMapper.selectById(tid);
            if (tenant != null && tenant.getSchemaName() != null) {
                TenantContext.setSchema(tenant.getSchemaName());
                tenantContextHelper.setTenantContext(tid, tenant.getSchemaName());
            } else {
                // 没有独立Schema时，至少设置RLS
                tenantContextHelper.setCurrentTenant(tid);
            }
        }
        if (userId != null) {
            TenantContext.setUserId(Long.valueOf(userId));
        }
        
        // 如果请求头中没有租户 ID，尝试从参数中获取（兼容前端）
        if (tenantId == null) {
            String paramTenantId = request.getParameter("tenantId");
            if (paramTenantId != null) {
                Long tid = Long.valueOf(paramTenantId);
                TenantContext.setTenantId(tid);
                // 同样查询租户Schema并切换，与请求头分支保持一致
                Tenant tenant = tenantMapper.selectById(tid);
                if (tenant != null && tenant.getSchemaName() != null) {
                    TenantContext.setSchema(tenant.getSchemaName());
                    tenantContextHelper.setTenantContext(tid, tenant.getSchemaName());
                } else {
                    tenantContextHelper.setCurrentTenant(tid);
                }
            }
        }
        
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
        tenantContextHelper.resetSchema();
    }
}
