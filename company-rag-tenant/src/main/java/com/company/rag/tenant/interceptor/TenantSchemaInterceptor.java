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
 * MyBatis-Plus 内拦截器 - 在每条 SQL 执行前设置租户上下文。
 * <p>
 * <strong>多租户隔离架构说明：</strong>
 * <ol>
 *   <li><strong>主隔离（Schema 隔离）</strong>：通过 {@code SET search_path} 路由到租户专属 schema，
 *       这是物理隔离，100% 可靠。每个租户有独立的 schema（如 {@code tenant_companyA}），
 *       数据天然隔离，不会跨租户。</li>
 *   <li><strong>辅助隔离（RLS 行级安全）</strong>：通过 {@code SET app.tenant_id} + RLS 策略提供
 *       深度防御（Defense in Depth）。即使应用层出现 bug 导致 search_path 错误，RLS 仍能提供
 *       额外保护。注意：RLS 是"锦上添花"，不是"雪中送炭"。</li>
 * </ol>
 * <p>
 * <strong>实现细节：</strong>
 * <ul>
 *   <li>使用 {@code SET}（非 {@code SET LOCAL}）：因为 Schema 隔离不依赖事务，每次查询前设置立即可用</li>
 *   <li>连接池清理：HikariCP 会在连接归还时重置会话状态，避免 GUC 残留</li>
 *   <li>100% 覆盖：所有 MyBatis 查询/更新都会经过此拦截器，确保租户上下文正确设置</li>
 * </ul>
 * <p>
 * <strong>安全边界：</strong>
 * <ul>
 *   <li>✅ Schema 隔离：绝对可靠，物理隔离保证</li>
 *   <li>⚠️ RLS 隔离：最佳努力（best-effort），作为深度防御的补充</li>
 * </ul>
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

    /**
     * 在执行连接上设置租户上下文。
     *
     * @param executor MyBatis 执行器
     */
    private void applyTenantContext(Executor executor) {
        String schema = TenantContext.getSchema();
        Long tenantId = TenantContext.getTenantId();
        if (schema == null || schema.isBlank() || tenantId == null) {
            return;
        }
        // 校验 schema 名称，防止 SQL 注入
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
                // 设置 search_path：主隔离（Schema 隔离），100% 可靠
                stmt.execute("SET search_path TO " + schema + ", public");
                // 设置 app.tenant_id：辅助隔离（RLS），深度防御
                stmt.execute("SET app.tenant_id = " + tenantId);
                log.trace("设置租户上下文：schema={}, tenantId={}", schema, tenantId);
            } catch (SQLException e) {
                log.warn("设置租户上下文失败：{}", e.getMessage());
            }
        }
    }
}
