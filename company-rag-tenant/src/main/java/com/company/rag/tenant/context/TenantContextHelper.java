package com.company.rag.tenant.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 租户上下文助手（已废弃）。
 * <p>
 * <strong>架构说明：</strong>
 * <p>
 * 数据库层隔离现在统一由 {@link com.company.rag.tenant.interceptor.TenantSchemaInterceptor} 负责：
 * <ul>
 *   <li>在每条 MyBatis 查询/更新执行前自动设置租户上下文</li>
 *   <li>主隔离（Schema 隔离）：通过 {@code SET search_path} 路由到租户专属 schema</li>
 *   <li>辅助隔离（RLS）：通过 {@code SET app.tenant_id} 提供深度防御</li>
 * </ul>
 * <p>
 * 此类的所有方法已废弃（no-op），不再执行任何数据库操作。
 */
@Slf4j
@Component
public class TenantContextHelper {

    /**
     * 设置租户上下文（已废弃）。
     * <p>
     * 此方法已废弃，不再执行任何操作。租户上下文由 {@code TenantSchemaInterceptor} 自动管理。
     *
     * @deprecated 见类注释
     */
    @Deprecated
    public void setTenantContext(Long tenantId, String schemaName) { /* no-op */ }

    /**
     * 设置当前租户 ID（已废弃）。
     *
     * @deprecated 见类注释
     */
    @Deprecated
    public void setCurrentTenant(Long tenantId) { /* no-op */ }

    /**
     * 设置 Schema（已废弃）。
     *
     * @deprecated 见类注释
     */
    @Deprecated
    public void setSchema(String schemaName) { /* no-op */ }

    /**
     * 重置 Schema（已废弃）。
     *
     * @deprecated 见类注释
     */
    @Deprecated
    public void resetSchema() { /* no-op */ }

    /**
     * 获取当前租户 ID（调试用）。
     * <p>
     * 注意：此方法需要连接已设置 GUC 才准确，实际使用中很少需要调用。
     *
     * @return 当前租户 ID（始终返回 null）
     * @deprecated 仅供调试，不保证准确性
     */
    @Deprecated
    public Long getCurrentTenant() { return null; }
}
