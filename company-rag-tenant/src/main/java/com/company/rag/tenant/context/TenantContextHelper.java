package com.company.rag.tenant.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 租户上下文助手 - 用于PostgreSQL RLS
 * 在执行数据库操作前,将租户ID设置到PostgreSQL session变量中
 * 这样RLS策略就能自动过滤数据
 */
@Slf4j
@Component
public class TenantContextHelper {

    private final JdbcTemplate jdbcTemplate;

    public TenantContextHelper(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 设置当前租户ID到PostgreSQL session变量
     * 必须在事务内调用,因为使用的是SET LOCAL
     */
    public void setCurrentTenant(Long tenantId) {
        if (tenantId != null) {
            try {
                // 使用参数化查询防止SQL注入
                jdbcTemplate.update("SELECT set_tenant_id(?)", tenantId);
                log.debug("已设置租户上下文: tenantId={}", tenantId);
            } catch (Exception e) {
                log.warn("设置租户上下文失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 获取当前PostgreSQL session中的租户ID
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
