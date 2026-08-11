package com.company.rag.agent.tool;

import com.company.rag.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 工具 - 数据库查询
 * 允许 Agent 通过自然语言查询业务数据库
 * 
 * 安全措施：
 * 1. 只允许 SELECT 查询（防止注入和修改）
 * 2. SQL 白名单检查（禁止危险关键字）
 * 3. 结果行数限制（默认 100 行）
 * 4. 多租户隔离（自动添加当前租户 schema 前缀，禁止跨租户访问）
 * 5. 禁止显式指定 schema（防止 SELECT * FROM tenant_other.table）
 */
@Slf4j
@Component
public class DatabaseQueryTool implements AgentTool {

    private final JdbcTemplate jdbcTemplate;
    private static final int MAX_ROWS = 100;
    
    /** 匹配表名的正则：FROM 或 JOIN 后面的表名（可带 schema 前缀） */
    private static final Pattern TABLE_PATTERN = Pattern.compile(
        "\\b(FROM|JOIN)\\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)?)",
        Pattern.CASE_INSENSITIVE
    );

    public DatabaseQueryTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getName() {
        return "database_query";
    }

    @Override
    public String getDescription() {
        return "查询企业业务数据库，获取订单、用户、产品等业务数据。仅支持 SELECT 查询。";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "sql", Map.of(
                        "type", "string",
                        "description", "SQL 查询语句（仅支持 SELECT）"
                ),
                "limit", Map.of(
                        "type", "integer",
                        "description", "返回行数限制（默认 100）",
                        "default", 100
                )
        ));
        schema.put("required", List.of("sql"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> params) {
        String sql = (String) params.get("sql");
        Integer limit = params.containsKey("limit") ?
                Integer.parseInt(params.get("limit").toString()) : MAX_ROWS;

        return queryDatabase(sql, limit);
    }

    /**
     * 数据库查询（@Tool 注解版本，供 Spring AI 自动调用）
     * @param sql SQL 查询语句（仅支持 SELECT）
     * @param limit 返回行数限制（默认 100）
     * @return 查询结果
     */
    @Tool(
        name = "database_query",
        description = """
            查询企业业务数据库，仅支持 SELECT 查询，返回表格格式结果。
            自动限制最多返回 100 行，禁止 DDL/DML 操作。
            
            适用场景：
            - 查询用户、订单、产品等业务数据
            - 例如："查询最近 7 天注册的用户"、"本月订单总数是多少？"、"库存低于 10 的产品有哪些？"
            
            不适用场景：
            - 知识库文档查询 -> 使用 searchKnowledgeBase
            """
    )
    public String queryDatabase(
            @ToolParam(description = "SQL 查询语句（仅支持 SELECT）", required = true) String sql,
            @ToolParam(description = "返回行数限制（默认 100）", required = false) Integer limit) {
        
        // 参数验证
        if (sql == null || sql.trim().isEmpty()) {
            return "错误：SQL 查询语句不能为空";
        }

        // 安全检查：只允许 SELECT
        String upperSql = sql.trim().toUpperCase();
        if (!upperSql.startsWith("SELECT")) {
            return "错误：仅支持 SELECT 查询";
        }

        // 检查危险关键字
        if (containsDangerousKeywords(upperSql)) {
            return "错误：SQL 包含禁止的操作";
        }

        // 租户隔离检查：确保租户上下文已设置
        String currentSchema = TenantContext.getSchema();
        if (currentSchema == null || currentSchema.isBlank()) {
            log.error("租户上下文未设置，拒绝查询：userId={}", TenantContext.getUserId());
            return "错误：未设置租户上下文，无法执行查询";
        }

        // 安全检查：禁止显式指定其他 schema（防止跨租户访问）
        if (containsExplicitSchema(sql)) {
            log.warn("检测到显式 schema 指定，拒绝跨租户访问：{}", sql);
            return "错误：禁止显式指定 schema，只能访问当前租户数据";
        }

        // 自动添加当前租户 schema 前缀
        String qualifiedSql = addSchemaPrefix(sql, currentSchema);
        log.info("Agent 执行数据库查询（租户：{}）：{}", currentSchema, qualifiedSql);

        // 添加 LIMIT 限制
        if (!upperSql.contains("LIMIT")) {
            qualifiedSql += " LIMIT " + Math.min(limit != null ? limit : MAX_ROWS, MAX_ROWS);
        }

        try {
            List<Map<String, Object>> result = jdbcTemplate.queryForList(qualifiedSql);
            return formatResult(result);
        } catch (Exception e) {
            log.error("数据库查询失败：{}", e.getMessage());
            return "查询失败：" + e.getMessage();
        }
    }

    /**
     * 获取表结构信息
     */
    public String describeTable(String tableName) {
        // 参数校验：防止表名注入
        if (!isValidTableName(tableName)) {
            return "错误：非法表名";
        }

        // 租户隔离检查
        String currentSchema = TenantContext.getSchema();
        if (currentSchema == null || currentSchema.isBlank()) {
            log.error("租户上下文未设置，拒绝查询表结构：userId={}", TenantContext.getUserId());
            return "错误：未设置租户上下文，无法获取表结构";
        }

        try {
            // 查询指定 schema 的表结构
            String sql = "SELECT column_name, data_type, is_nullable " +
                    "FROM information_schema.columns WHERE table_schema = ? AND table_name = ?";
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(sql, currentSchema, tableName);
            return formatResult(columns);
        } catch (Exception e) {
            return "获取表结构失败：" + e.getMessage();
        }
    }

    /**
     * 检查 SQL 是否包含危险关键字
     */
    private boolean containsDangerousKeywords(String upperSql) {
        String[] dangerousKeywords = {
            "DROP", "DELETE", "UPDATE", "INSERT", 
            "TRUNCATE", "ALTER", "CREATE", "GRANT",
            "REVOKE", "EXEC", "EXECUTE", "XP_",
            "SP_", "SCRIPT", "JAVASCRIPT", "VBSCRIPT"
        };
        
        for (String keyword : dangerousKeywords) {
            if (upperSql.contains(keyword)) {
                log.warn("检测到危险 SQL 关键字：{}", keyword);
                return true;
            }
        }
        return false;
    }

    /**
     * 检查 SQL 是否显式指定了 schema（防止跨租户访问）
     * 例如：SELECT * FROM tenant_other.table 是被禁止的
     */
    private boolean containsExplicitSchema(String sql) {
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String tableName = matcher.group(2);
            // 如果表名包含 . 说明显式指定了 schema
            if (tableName.contains(".")) {
                // 允许 public. 前缀（会被 TenantAwareJdbcTemplate 替换）
                if (!tableName.startsWith("public.")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 为 SQL 中的所有表名添加当前租户的 schema 前缀
     */
    private String addSchemaPrefix(String sql, String schema) {
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        
        while (matcher.find()) {
            String keyword = matcher.group(1);
            String tableName = matcher.group(2);
            
            // 如果表名已经有 public. 前缀，替换为当前租户 schema
            if (tableName.startsWith("public.")) {
                String actualTable = tableName.substring(7);
                result.append(sql, lastEnd, matcher.start(2));
                result.append(schema).append(".").append(actualTable);
            }
            // 如果表名没有 schema 前缀，添加当前租户 schema
            else if (!tableName.contains(".")) {
                result.append(sql, lastEnd, matcher.start(2));
                result.append(schema).append(".").append(tableName);
            }
            // 其他情况保持原样（理论上不会发生，因为 containsExplicitSchema 已经检查过）
            else {
                continue;
            }
            lastEnd = matcher.end(2);
        }
        
        if (lastEnd > 0) {
            result.append(sql.substring(lastEnd));
            return result.toString();
        }
        return sql;
    }

    /**
     * 验证表名是否合法（只允许字母、数字、下划线）
     */
    private boolean isValidTableName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return false;
        }
        return tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$");
    }

    private String formatResult(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "查询结果为空";
        
        StringBuilder sb = new StringBuilder();
        sb.append("查询结果（").append(rows.size()).append("行）：\n\n");
        
        // 表头
        if (!rows.isEmpty()) {
            sb.append(String.join(" | ", rows.get(0).keySet())).append("\n");
            sb.append("-".repeat(80)).append("\n");
            
            // 数据行
            for (Map<String, Object> row : rows) {
                sb.append(row.values().stream()
                        .map(v -> v != null ? v.toString() : "NULL")
                        .reduce((a, b) -> a + " | " + b)
                        .orElse(""))
                      .append("\n");
            }
        }
        
        return sb.toString();
    }
}
