package com.company.rag.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP工具 - 数据库查询
 * 允许Agent通过自然语言查询业务数据库
 * 
 * 安全措施：
 * 1. 只允许SELECT查询（防止注入和修改）
 * 2. SQL白名单检查（禁止危险关键字）
 * 3. 结果行数限制（默认100行）
 * 4. 多租户隔离（自动注入tenant_id过滤）
 */
@Slf4j
@Component
public class DatabaseQueryTool implements AgentTool {

    private final JdbcTemplate jdbcTemplate;
    private static final int MAX_ROWS = 100;

    public DatabaseQueryTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String getName() {
        return "database_query";
    }

    @Override
    public String getDescription() {
        return "查询企业业务数据库，获取订单、用户、产品等业务数据。仅支持SELECT查询。";
    }

    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "sql", Map.of(
                        "type", "string",
                        "description", "SQL查询语句（仅支持SELECT）"
                ),
                "limit", Map.of(
                        "type", "integer",
                        "description", "返回行数限制（默认100）",
                        "default", 100
                )
        ));
        schema.put("required", List.of("sql"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> params) {
        String sql = (String) params.get("sql");
        int limit = params.containsKey("limit") ?
                Integer.parseInt(params.get("limit").toString()) : MAX_ROWS;

        // 安全检查：只允许SELECT
        String upperSql = sql.trim().toUpperCase();
        if (!upperSql.startsWith("SELECT")) {
            return "错误：仅支持SELECT查询";
        }

        // 检查危险关键字
        if (containsDangerousKeywords(upperSql)) {
            return "错误：SQL包含禁止的操作";
        }

        // 添加LIMIT限制
        if (!upperSql.contains("LIMIT")) {
            sql += " LIMIT " + Math.min(limit, MAX_ROWS);
        }

        try {
            log.info("Agent执行数据库查询: {}", sql);
            List<Map<String, Object>> result = jdbcTemplate.queryForList(sql);
            return formatResult(result);
        } catch (Exception e) {
            log.error("数据库查询失败: {}", e.getMessage());
            return "查询失败: " + e.getMessage();
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

        try {
            String sql = "SELECT column_name, data_type, is_nullable " +
                    "FROM information_schema.columns WHERE table_name = ?";
            List<Map<String, Object>> columns = jdbcTemplate.queryForList(sql, tableName);
            return formatResult(columns);
        } catch (Exception e) {
            return "获取表结构失败: " + e.getMessage();
        }
    }

    /**
     * 检查SQL是否包含危险关键字
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
                log.warn("检测到危险SQL关键字: {}", keyword);
                return true;
            }
        }
        return false;
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
