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
 * MyBatis-Plus 内拦截器 - 在执行 SQL 前自动设置 search_path
 * <p>
 * 解决时序竞争问题：TenantInterceptor 通过 JdbcTemplate 设置 search_path 后，
 * MyBatis 可能从连接池获取到另一个未设置 search_path 的连接，导致查询租户 Schema 下的表失败。
 * <p>
 * 该拦截器在每次 MyBatis 查询/更新前，在 MyBatis 当前使用的连接上直接执行 SET search_path，
 * 确保 search_path 始终与当前租户 Schema 一致。
 * <p>
 * 与 TenantAwareJdbcTemplate 互补：
 * - TenantAwareJdbcTemplate：替换 SQL 中的 public. 前缀（用于 Spring AI PgVectorStore 等）
 * - TenantSchemaInterceptor：在 MyBatis 连接上设置 search_path（用于 MyBatis 查询）
 */
@Slf4j
public class TenantSchemaInterceptor implements InnerInterceptor {

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter,
                            RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        setSearchPath(executor);
    }

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter) {
        setSearchPath(executor);
    }

    /**
     * 在当前 MyBatis 连接上设置 search_path
     */
    private void setSearchPath(Executor executor) {
        String schema = TenantContext.getSchema();
        if (schema == null || schema.isBlank()) {
            return;
        }

        // 校验 Schema 名称合法性，防止 SQL 注入
        if (!schema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            log.warn("非法 Schema 名称，跳过 search_path 设置：{}", schema);
            return;
        }

        Connection connection = null;
        try {
            // 从 Executor 获取当前连接
            connection = executor.getTransaction().getConnection();
        } catch (SQLException e) {
            log.warn("获取 MyBatis 连接失败，跳过 search_path 设置", e);
            return;
        }

        if (connection != null) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SET search_path TO " + schema + ", public");
                log.trace("MyBatis 拦截器设置 search_path: {}", schema);
            } catch (SQLException e) {
                log.warn("设置 search_path 失败：{}", e.getMessage());
            }
        }
    }
}