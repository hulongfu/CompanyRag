package com.company.rag.tenant.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 租户上下文助手 - 用于 PostgreSQL RLS + Schema 隔离
 */
@Slf4j
@Component
public class TenantContextHelper {

    private final JdbcTemplate jdbcTemplate;

    public TenantContextHelper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 设置当前租户的完整上下文（RLS + Schema）
     * 在每次数据库操作前调用
     */
    public void setTenantContext(Long tenantId, String schemaName) {
        if (tenantId != null) {
            setCurrentTenant(tenantId);
        }
        if (schemaName != null) {
            setSchema(schemaName);
        }
    }

    /**
     * 设置当前租户 ID 到 PostgreSQL session 变量
     * 使用 SET 而不是 SET LOCAL，因为拦截器在事务外调用
     */
    public void setCurrentTenant(Long tenantId) {
        if (tenantId != null) {
            try {
                // 使用 SET 而不是 SET LOCAL，因为拦截器在事务外调用
                // SET 在整个 session 生效，SET LOCAL 只在当前事务生效
                // 注意：SET 命令不能使用参数化查询，需要拼接 SQL
                jdbcTemplate.update("SET app.tenant_id = " + tenantId);
                log.debug("已设置租户上下文：tenantId={}", tenantId);
            } catch (Exception e) {
                log.warn("设置租户上下文失败：{}", e.getMessage());
            }
        }
    }

    /**
     * 切换当前数据库 Schema
     * 通过 SET search_path 实现，后续 SQL 操作将自动路由到对应 Schema
     */
    public void setSchema(String schemaName) {
        if (schemaName != null && !schemaName.isBlank()) {
            try {
                // 校验 Schema 名称合法性
                if (!schemaName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
                    log.warn("非法 Schema 名称，跳过切换：{}", schemaName);
                    return;
                }
                // 设置 search_path：租户 Schema 优先，public 作为后备
                jdbcTemplate.update("SET search_path TO " + schemaName + ", public");
                log.debug("已切换 Schema: {}", schemaName);
            } catch (Exception e) {
                log.warn("切换 Schema 失败：{}", e.getMessage());
            }
        }
    }

    /**
     * 重置为默认 Schema
     */
    public void resetSchema() {
        try {
            jdbcTemplate.update("SET search_path TO public");
        } catch (Exception e) {
            log.warn("重置 Schema 失败：{}", e.getMessage());
        }
    }

    /**
     * 获取当前 PostgreSQL session 中的租户 ID
     */
    public Long getCurrentTenant() {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT current_tenant_id()", 
                Long.class
            );
        } catch (Exception e) {
            return null;
        }
    }
}
