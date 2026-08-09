package com.company.rag.tenant.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 租户上下文助手。
 * <p>
 * 数据库层隔离（search_path + RLS 的 app.tenant_id）现在统一由
 * {@link com.company.rag.tenant.interceptor.TenantSchemaInterceptor} 在每条
 * MyBatis 语句的执行连接上设置并在语句结束后清除。
 * 此类不再执行任何 SET 语句（会话级 SET 会导致 GUC 残留在连接池、串租户）。
 */
@Slf4j
@Component
public class TenantContextHelper {

    /** @deprecated DB 上下文已由 TenantSchemaInterceptor 处理，请勿再调用。 */
    @Deprecated
    public void setTenantContext(Long tenantId, String schemaName) { /* no-op */ }

    /** @deprecated 见类注释。 */
    @Deprecated
    public void setCurrentTenant(Long tenantId) { /* no-op */ }

    /** @deprecated 见类注释。 */
    @Deprecated
    public void setSchema(String schemaName) { /* no-op */ }

    /** @deprecated 见类注释。 */
    @Deprecated
    public void resetSchema() { /* no-op */ }

    /** 调试用：读取当前连接租户 ID（须在已设置 GUC 的连接上调用才准确）。 */
    public Long getCurrentTenant() { return null; }
}
