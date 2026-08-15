package com.company.rag.agent.tool;

import com.company.rag.agent.security.SqlSecurityValidator;
import com.company.rag.common.exception.BizException;
import com.company.rag.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    
    /** 
     * 匹配表名的正则：FROM 或 JOIN 后面的表名（可带 schema 前缀）
     * 增强：支持匹配子查询中的 FROM/JOIN（通过括号深度匹配）
     */
    private static final Pattern TABLE_PATTERN = Pattern.compile(
        "\\b(FROM|JOIN)\\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)?)",
        Pattern.CASE_INSENSITIVE
    );
    
    /** 匹配 SQL 注释的正则：-- 或 /* 注释 */
    private static final Pattern COMMENT_PATTERN = Pattern.compile(
        "(--[^\\n]*|/\\*.*?\\*/)",
        Pattern.DOTALL
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

        // ✅ 第一道防线：移除 SQL 注释（防止注释绕过）
        String cleanSql = removeComments(sql);
        
        // ✅ 第二道防线：使用 JSqlParser 进行严格语法分析
        try {
            SqlSecurityValidator.validateSelectSql(cleanSql);
        } catch (BizException e) {
            log.warn("JSqlParser 验证失败：{}", e.getMessage());
            return "错误：" + e.getMessage();
        }

        // 安全检查：只允许 SELECT（JSqlParser 已验证，这里是双重检查）
        String upperSql = cleanSql.trim().toUpperCase();
        if (!upperSql.startsWith("SELECT")) {
            return "错误：仅支持 SELECT 查询";
        }

        // 检查危险关键字（保留作为辅助检查）
        if (containsDangerousKeywords(upperSql)) {
            return "错误：SQL 包含禁止的操作";
        }

        // 租户隔离检查：确保租户上下文已设置
        String currentSchema = TenantContext.getSchema();
        if (currentSchema == null || currentSchema.isBlank()) {
            log.error("租户上下文未设置，拒绝查询：userId={}", TenantContext.getUserId());
            return "错误：未设置租户上下文，无法执行查询";
        }

        // ✅ 第三道防线：检查显式 schema 指定（包括子查询）
        if (containsExplicitSchema(cleanSql)) {
            log.warn("检测到显式 schema 指定，拒绝跨租户访问：{}", cleanSql);
            return "错误：禁止显式指定 schema，只能访问当前租户数据";
        }

        // 自动添加当前租户 schema 前缀（使用移除注释后的 SQL）
        String qualifiedSql = addSchemaPrefix(cleanSql, currentSchema);
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
     * 移除 SQL 中的注释（防止注释绕过检查）
     */
    private String removeComments(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        // 移除所有注释
        return COMMENT_PATTERN.matcher(sql).replaceAll("");
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
     * 
     * 增强：检测所有表名（包括子查询中的表）
     * 增强：支持带引号的标识符（例如："tenant_other"."secret"）
     */
    private boolean containsExplicitSchema(String sql) {
        // 使用 JSqlParser 提取所有表名并检查 schema（包括子查询）
        try {
            // 直接遍历 JSqlParser 解析后的表对象，检查 getSchemaName()
            return hasExplicitSchemaInSelect(sql);
        } catch (Exception e) {
            // JSqlParser 解析失败，降级使用正则检查
            log.debug("JSqlParser 检查 schema 失败，降级使用正则检查：{}", e.getMessage());
            Matcher matcher = TABLE_PATTERN.matcher(sql);
            while (matcher.find()) {
                String tableName = matcher.group(2);
                if (tableName.contains(".")) {
                    if (!tableName.startsWith("public.")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * 检查 Select 语句中是否有表显式指定了 schema
     * 支持带引号的标识符（例如："tenant_other"."secret"）
     */
    private boolean hasExplicitSchemaInSelect(String sql) throws Exception {
        net.sf.jsqlparser.statement.Statement statement = 
            net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql);
        
        if (!(statement instanceof net.sf.jsqlparser.statement.select.Select)) {
            return false;
        }
        
        net.sf.jsqlparser.statement.select.Select select = 
            (net.sf.jsqlparser.statement.select.Select) statement;
        
        return hasExplicitSchemaInSelectObject(select);
    }
    
    /**
     * 递归检查 Select 对象中的所有表是否显式指定了 schema
     * 检查范围：FROM、JOIN、SELECT 列表、WHERE、HAVING、GROUP BY、ORDER BY
     */
    private boolean hasExplicitSchemaInSelectObject(
            net.sf.jsqlparser.statement.select.Select select) {
        if (select instanceof net.sf.jsqlparser.statement.select.SetOperationList) {
            return false;
        }
        
        net.sf.jsqlparser.statement.select.PlainSelect plainSelect = 
            select.getPlainSelect();
        if (plainSelect == null) {
            return false;
        }
        
        // 检查 FROM 子句
        if (hasExplicitSchemaInFromItem(plainSelect.getFromItem())) {
            return true;
        }
        
        // 检查 JOIN
        if (plainSelect.getJoins() != null) {
            for (net.sf.jsqlparser.statement.select.Join join : plainSelect.getJoins()) {
                if (hasExplicitSchemaInFromItem(join.getRightItem())) {
                    return true;
                }
            }
        }
        
        // 检查 SELECT 列表中的子查询
        if (plainSelect.getSelectItems() != null) {
            for (net.sf.jsqlparser.statement.select.SelectItem selectItem : plainSelect.getSelectItems()) {
                if (hasExplicitSchemaInSelectItem(selectItem)) {
                    return true;
                }
            }
        }
        
        // 检查 WHERE 子句中的子查询
        if (plainSelect.getWhere() != null) {
            if (hasExplicitSchemaInExpression(plainSelect.getWhere())) {
                return true;
            }
        }
        
        // 检查 HAVING 子句中的子查询
        if (plainSelect.getHaving() != null) {
            if (hasExplicitSchemaInExpression(plainSelect.getHaving())) {
                return true;
            }
        }
        
        // 检查 GROUP BY 中的子查询
        if (plainSelect.getGroupBy() != null && plainSelect.getGroupBy().getGroupByExpressions() != null) {
            for (Object groupExprObj : plainSelect.getGroupBy().getGroupByExpressions()) {
                if (groupExprObj instanceof net.sf.jsqlparser.expression.Expression) {
                    if (hasExplicitSchemaInExpression((net.sf.jsqlparser.expression.Expression) groupExprObj)) {
                        return true;
                    }
                }
            }
        }
        
        // 检查 ORDER BY 中的子查询
        if (plainSelect.getOrderByElements() != null) {
            for (net.sf.jsqlparser.statement.select.OrderByElement orderBy : plainSelect.getOrderByElements()) {
                if (hasExplicitSchemaInExpression(orderBy.getExpression())) {
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * 检查 SelectItem 是否包含显式指定 schema 的子查询
     */
    private boolean hasExplicitSchemaInSelectItem(
            net.sf.jsqlparser.statement.select.SelectItem selectItem) {
        if (selectItem == null) {
            return false;
        }
        
        // 检查表达式（可能包含子查询）
        if (selectItem.getExpression() != null) {
            return hasExplicitSchemaInExpression(selectItem.getExpression());
        }
        
        return false;
    }
    
    /**
     * 递归检查 Expression 中是否包含显式指定 schema 的子查询
     * 简化版本：直接检查 ParenthesedSelect 和 InExpression
     */
    private boolean hasExplicitSchemaInExpression(
            net.sf.jsqlparser.expression.Expression expression) {
        if (expression == null) {
            return false;
        }
        
        // 检查直接子查询
        if (expression instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect) {
            net.sf.jsqlparser.statement.select.ParenthesedSelect parenthesedSelect = 
                (net.sf.jsqlparser.statement.select.ParenthesedSelect) expression;
            return hasExplicitSchemaInSelectObject(parenthesedSelect.getSelect());
        }
        
        // 检查 IN 子句中的子查询
        if (expression instanceof net.sf.jsqlparser.expression.operators.relational.InExpression) {
            net.sf.jsqlparser.expression.operators.relational.InExpression inExpr = 
                (net.sf.jsqlparser.expression.operators.relational.InExpression) expression;
            // 检查右表达式是否是子查询
            if (inExpr.getRightExpression() instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect) {
                net.sf.jsqlparser.statement.select.ParenthesedSelect subSelect = 
                    (net.sf.jsqlparser.statement.select.ParenthesedSelect) inExpr.getRightExpression();
                return hasExplicitSchemaInSelectObject(subSelect.getSelect());
            }
        }
        
        // 检查 EXISTS 子句
        if (expression instanceof net.sf.jsqlparser.expression.operators.relational.ExistsExpression) {
            net.sf.jsqlparser.expression.operators.relational.ExistsExpression existsExpr = 
                (net.sf.jsqlparser.expression.operators.relational.ExistsExpression) expression;
            if (existsExpr.getRightExpression() instanceof net.sf.jsqlparser.statement.select.ParenthesedSelect) {
                net.sf.jsqlparser.statement.select.ParenthesedSelect subSelect = 
                    (net.sf.jsqlparser.statement.select.ParenthesedSelect) existsExpr.getRightExpression();
                return hasExplicitSchemaInSelectObject(subSelect.getSelect());
            }
        }
        
        // 检查比较表达式（=、<> 等）的左右两边
        if (expression instanceof net.sf.jsqlparser.expression.operators.relational.ComparisonOperator) {
            net.sf.jsqlparser.expression.operators.relational.ComparisonOperator compExpr = 
                (net.sf.jsqlparser.expression.operators.relational.ComparisonOperator) expression;
            if (compExpr.getLeftExpression() != null) {
                if (hasExplicitSchemaInExpression(compExpr.getLeftExpression())) {
                    return true;
                }
            }
            if (compExpr.getRightExpression() != null) {
                if (hasExplicitSchemaInExpression(compExpr.getRightExpression())) {
                    return true;
                }
            }
        }
        
        // 检查 AND/OR 表达式
        if (expression instanceof net.sf.jsqlparser.expression.operators.conditional.AndExpression) {
            net.sf.jsqlparser.expression.operators.conditional.AndExpression andExpr = 
                (net.sf.jsqlparser.expression.operators.conditional.AndExpression) expression;
            return hasExplicitSchemaInExpression(andExpr.getLeftExpression()) ||
                   hasExplicitSchemaInExpression(andExpr.getRightExpression());
        }
        
        if (expression instanceof net.sf.jsqlparser.expression.operators.conditional.OrExpression) {
            net.sf.jsqlparser.expression.operators.conditional.OrExpression orExpr = 
                (net.sf.jsqlparser.expression.operators.conditional.OrExpression) expression;
            return hasExplicitSchemaInExpression(orExpr.getLeftExpression()) ||
                   hasExplicitSchemaInExpression(orExpr.getRightExpression());
        }
        
        return false;
    }
    
    /**
     * 递归检查 FromItem 是否显式指定了 schema
     */
    private boolean hasExplicitSchemaInFromItem(
            net.sf.jsqlparser.statement.select.FromItem fromItem) {
        if (fromItem instanceof net.sf.jsqlparser.schema.Table) {
            net.sf.jsqlparser.schema.Table table = 
                (net.sf.jsqlparser.schema.Table) fromItem;
            // 检查是否有 schema（包括带引号的 schema）
            return table.getSchemaName() != null;
        } else if (fromItem instanceof 
                net.sf.jsqlparser.statement.select.ParenthesedSelect) {
            // 递归检查子查询
            net.sf.jsqlparser.statement.select.ParenthesedSelect parenthesedSelect = 
                (net.sf.jsqlparser.statement.select.ParenthesedSelect) fromItem;
            return hasExplicitSchemaInSelectObject(parenthesedSelect.getSelect());
        } else if (fromItem instanceof 
                net.sf.jsqlparser.statement.select.ParenthesedFromItem) {
            // 括号包裹的项
            net.sf.jsqlparser.statement.select.ParenthesedFromItem parenthesis = 
                (net.sf.jsqlparser.statement.select.ParenthesedFromItem) fromItem;
            return hasExplicitSchemaInFromItem(parenthesis.getFromItem());
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
