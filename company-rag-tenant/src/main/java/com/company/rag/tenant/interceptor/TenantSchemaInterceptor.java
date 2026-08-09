package com.company.rag.tenant.interceptor;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.company.rag.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MyBatis-Plus 内拦截器 - 在每条语句的"执行连接"上设置租户上下文。
 * <p>
 * 同时负责：(1) search_path 路由到租户 schema；(2) RLS 所需的 app.tenant_id。
 * 关键：使用 SET 而非 SET LOCAL，在每次查询前设置，确保作用在执行连接上。
 * 注意：GUC 会在连接归还池后残留，因此依赖连接池的自动清理（HikariCP 默认会 reset）。
 */
@Slf4j
public class TenantSchemaInterceptor implements InnerInterceptor {

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        applyTenantContext(executor);
    }

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter) {
        applyTenantContext(executor);
    }

    /** 在执行连接上设置 search_path 与 RLS 租户标识（使用 SET，连接池会自动清理） */
    private void applyTenantContext(Executor executor) {
        String schema = TenantContext.getSchema();
        Long tenantId = TenantContext.getTenantId();
        if (schema == null || schema.isBlank() || tenantId == null) {
            return;
        }
        if (!schema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            log.warn("非法 Schema 名称，跳过租户上下文设置：{}", schema);
            return;
        }
        Connection connection;
        try {
            connection = executor.getTransaction().getConnection();
        } catch (SQLException e) {
            log.warn("获取 MyBatis 连接失败，跳过租户上下文设置", e);
            return;
        }
        if (connection != null) {
            try (Statement stmt = connection.createStatement()) {
                // 使用 SET：在当前连接上设置，连接归还池时由 HikariCP 自动 reset
                stmt.execute("SET search_path TO " + schema + ", public");
                stmt.execute("SET app.tenant_id = " + tenantId);
                log.trace("设置租户上下文：schema={}, tenantId={}", schema, tenantId);
            } catch (SQLException e) {
                log.warn("设置租户上下文失败：{}", e.getMessage());
            }
        }
    }
}
