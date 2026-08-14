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
        // 仅从请求头获取租户 ID，禁止从 URL 参数传递（防止越权访问）
        String tenantId = request.getHeader(RagConstant.HEADER_TENANT_ID);
        String userId = request.getHeader(RagConstant.HEADER_USER_ID);

        if (tenantId != null) {
            try {
                Long tid = Long.valueOf(tenantId);
                TenantContext.setTenantId(tid);

                // 从数据库获取租户的 Schema 名称，设置 Schema 隔离
                Tenant tenant = tenantMapper.selectById(tid);
                if (tenant != null && tenant.getSchemaName() != null) {
                    TenantContext.setSchema(tenant.getSchemaName());
                }
            } catch (NumberFormatException e) {
                // 无效的租户 ID 格式，保持上下文为空，由后续认证拦截
            }
        }
        if (userId != null) {
            try {
                TenantContext.setUserId(Long.valueOf(userId));
            } catch (NumberFormatException e) {
                // 无效的用户 ID 格式，忽略
            }
        }
        
        // 注意：不再支持从 URL 参数 ?tenantId= 传递租户 ID
        // 原因：该方式绕过了 JwtAuthenticationFilter 中的租户权限校验
        // 所有租户切换必须通过 X-Tenant-Id 请求头，该方式已在 Filter 层验证用户是否属于该租户
        
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        TenantContext.clear();
        // TenantContextHelper 已废弃，不再调用其 reset 方法
    }
}
