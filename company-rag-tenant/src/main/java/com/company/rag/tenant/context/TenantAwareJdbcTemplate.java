package com.company.rag.tenant.context;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.PreparedStatementSetter;

import java.util.List;

/**
 * 租户感知的 JdbcTemplate
 * <p>
 * 重写 SQL 执行方法，将硬编码的 {@code public.} schema 前缀替换为当前租户的 schema，
 * 使 PgVectorStore 等硬编码 public schema 的组件能正确路由到租户业务表。
 */
public class TenantAwareJdbcTemplate extends JdbcTemplate {

    public TenantAwareJdbcTemplate(JdbcTemplate delegate) {
        // 复用 delegate 的数据源和配置
        setDataSource(delegate.getDataSource());
        setExceptionTranslator(delegate.getExceptionTranslator());
        setFetchSize(delegate.getFetchSize());
        setMaxRows(delegate.getMaxRows());
        setQueryTimeout(delegate.getQueryTimeout());
    }

    /**
     * 将 SQL 中的 public. 前缀替换为当前租户 schema
     */
    private String resolveSchema(String sql) {
        String schema = TenantContext.getSchema();
        if (schema != null && !schema.isBlank()) {
            return sql.replace("public.", schema + ".");
        }
        return sql;
    }

    @Override
    public int[] batchUpdate(String sql, BatchPreparedStatementSetter pss) {
        return super.batchUpdate(resolveSchema(sql), pss);
    }

    @Override
    public void execute(String sql) {
        super.execute(resolveSchema(sql));
    }

    @Override
    public int update(String sql) {
        return super.update(resolveSchema(sql));
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType) {
        return super.queryForObject(resolveSchema(sql), requiredType);
    }

    // ========== query 系列方法重写 ==========

    @Override
    public <T> T query(String sql, ResultSetExtractor<T> rse) {
        return super.query(resolveSchema(sql), rse);
    }

    @Override
    public void query(String sql, RowCallbackHandler rch) {
        super.query(resolveSchema(sql), rch);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
        return super.query(resolveSchema(sql), rowMapper);
    }

    @Override
    public <T> T query(String sql, PreparedStatementSetter pss, ResultSetExtractor<T> rse) {
        return super.query(resolveSchema(sql), pss, rse);
    }

    @Override
    public <T> T query(String sql, Object[] args, ResultSetExtractor<T> rse) {
        return super.query(resolveSchema(sql), args, rse);
    }

    @Override
    public <T> T query(String sql, ResultSetExtractor<T> rse, Object... args) {
        return super.query(resolveSchema(sql), rse, args);
    }

    @Override
    public <T> List<T> query(String sql, PreparedStatementSetter pss, RowMapper<T> rowMapper) {
        return super.query(resolveSchema(sql), pss, rowMapper);
    }

    @Override
    public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper) {
        return super.query(resolveSchema(sql), args, rowMapper);
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        return super.query(resolveSchema(sql), rowMapper, args);
    }
}
