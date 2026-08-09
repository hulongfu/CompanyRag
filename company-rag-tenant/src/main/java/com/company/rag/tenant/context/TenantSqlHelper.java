package com.company.rag.tenant.context;

import com.company.rag.common.exception.BizException;

/**
 * 租户 SQL 辅助工具类 - 提供安全的表名拼接和租户上下文获取
 * 
 * 用于 JdbcTemplate 等不经过 MyBatis 拦截器的场景，确保租户隔离
 */
public class TenantSqlHelper {

    /**
     * 获取带 schema 限定的表名
     * 防止 SQL 注入：只允许字母、数字、下划线
     * 
     * @param schema 租户 schema 名称
     * @param tableName 表名
     * @return 带 schema 限定的完整表名（格式：schema.tableName）
     * @throws BizException 当 schema 或 tableName 为 null、空或非法格式时抛出
     */
    public static String getQualifiedTableName(String schema, String tableName) {
        if (schema == null || schema.isBlank()) {
            throw new BizException("租户 schema 不能为空");
        }
        if (!schema.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new BizException("非法租户 schema 名称：" + schema);
        }
        if (tableName == null || tableName.isBlank()) {
            throw new BizException("表名不能为空");
        }
        if (!tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new BizException("非法表名：" + tableName);
        }
        return schema + "." + tableName;
    }

    /**
     * 获取当前租户 schema，如果为空则抛异常
     * 
     * @return 当前租户 schema 名称
     * @throws BizException 当未设置租户上下文时抛出
     */
    public static String requireSchema() {
        String schema = TenantContext.getSchema();
        if (schema == null || schema.isBlank()) {
            throw new BizException("未设置租户上下文");
        }
        return schema;
    }

    /**
     * 获取当前租户 schema，如果为空则返回 null（不抛异常）
     * 
     * @return 当前租户 schema 名称，可能为 null
     */
    public static String getSchemaOrNull() {
        return TenantContext.getSchema();
    }
}
