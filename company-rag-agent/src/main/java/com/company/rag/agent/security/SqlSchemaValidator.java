package com.company.rag.agent.security;

import com.company.rag.agent.config.Nl2sqlSchemaValidationProperties;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.schema.Table;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * nl2sql 表/列名自校验器。
 * <p>校验 LLM 生成的 SELECT 语句引用的表/列是否存在于当前租户 schema。
 * 原则：宁漏不误 —— 任何解析/元数据异常一律降级放行（返回 null），绝不让加固引入新失败模式。
 * 校验范围由 spec §3.3 定义：表全量、列窄范围（单表无子查询无 CTE）。</p>
 */
@Slf4j
@Component
public class SqlSchemaValidator {

    private static final int MAX_CANDIDATES = 3;
    /** 敏感标识符集合（脱敏，避免把表结构细节回显给 LLM） */
    private static final Set<String> SENSITIVE_NAMES =
            Set.of("password", "passwd", "secret", "token", "api_key", "apikey", "access_key");

    private final JdbcTemplate jdbcTemplate;
    private final Nl2sqlSchemaValidationProperties properties;

    public SqlSchemaValidator(JdbcTemplate jdbcTemplate,
                              Nl2sqlSchemaValidationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /** 校验 SQL；返回 null 表示通过或降级放行，非 null 为已含 {@code 错误：} 前缀的错误文本。 */
    public String validate(String sql, String schema) {
        if (!isEnabled() || !StringUtils.hasText(sql) || !StringUtils.hasText(schema)) {
            return null;
        }
        try {
            Statement st = CCJSqlParserUtil.parse(sql);
            if (!(st instanceof Select select) || select instanceof SetOperationList) {
                return null; // 非 SELECT 或集合操作（外层已拦，理论不达），降级放行
            }
            PlainSelect ps = select.getPlainSelect();
            if (ps == null) {
                return null;
            }

            // 表校验：元数据（可用表）查询失败 → 整体降级放行
            Set<String> existing = loadExistingTables(schema);
            if (existing == null) {
                return null;
            }
            List<String> missingTables = checkTables(ps, select, existing);
            List<String> missingColumns = checkColumns(ps, select, schema);

            if (missingTables.isEmpty() && missingColumns.isEmpty()) {
                return null;
            }
            return buildErrorText(missingTables, missingColumns, existing);
        } catch (Exception e) {
            log.warn("nl2sql 表/列名校验降级放行：{}", e.getMessage());
            return null;
        }
    }

    private boolean isEnabled() {
        try {
            return properties != null && properties.isEnabled();
        } catch (Exception e) {
            return true; // 读取配置异常时默认启用
        }
    }

    /* ---------------- 表校验 ---------------- */

    private List<String> checkTables(PlainSelect ps, Select select, Set<String> existing) {
        Set<String> cteNames = new LinkedHashSet<>();
        if (select.getWithItemsList() != null) {
            for (WithItem wi : select.getWithItemsList()) {
                if (wi != null && wi.getAlias() != null) {
                    cteNames.add(wi.getAlias().getName().toLowerCase());
                }
            }
        }
        Set<String> referenced = new LinkedHashSet<>();
        collectTables(ps.getFromItem(), referenced, cteNames);
        if (ps.getJoins() != null) {
            for (Join j : ps.getJoins()) {
                collectTables(j.getRightItem(), referenced, cteNames);
            }
        }
        if (referenced.isEmpty()) {
            return List.of();
        }
        return referenced.stream()
                .filter(n -> !existing.contains(n))
                .collect(Collectors.toList());
    }

    /** 收集 FROM/JOIN 中引用的裸表名；跳过 CTE 名、显式 schema 表，递归子查询内层真实表。 */
    private void collectTables(FromItem item, Set<String> out, Set<String> cteNames) {
        if (item instanceof Table table) {
            if (table.getSchemaName() != null) {
                return; // 显式 schema（系统性 / 已限定），跳过
            }
            String name = table.getName();
            if (name != null && !cteNames.contains(name.toLowerCase())) {
                out.add(name.toLowerCase());
            }
        } else if (item instanceof Select sub) {
            PlainSelect subPs = sub.getPlainSelect();
            if (subPs != null) {
                collectTables(subPs.getFromItem(), out, cteNames);
                if (subPs.getJoins() != null) {
                    for (Join j : subPs.getJoins()) {
                        collectTables(j.getRightItem(), out, cteNames);
                    }
                }
            }
        }
    }

    /** 查询当前 schema 下所有业务表名（小写归一）；失败返回 null（降级）。 */
    private Set<String> loadExistingTables(String schema) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                    schema);
            Set<String> tables = new LinkedHashSet<>();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Object v = row.get("table_name");
                    if (v != null) {
                        tables.add(v.toString().toLowerCase());
                    }
                }
            }
            return tables;
        } catch (Exception e) {
            log.warn("查询租户 schema({}) 可用表失败，跳过表校验：{}", schema, e.getMessage());
            return null;
        }
    }

    /* ---------------- 列校验 ---------------- */

    private List<String> checkColumns(PlainSelect ps, Select select, String schema) {
        // 前置条件：仅「单表、无 JOIN、无 CTE、无子查询/派生表」才校验
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            return List.of();
        }
        if (ps.getJoins() != null && !ps.getJoins().isEmpty()) {
            return List.of();
        }
        if (!(ps.getFromItem() instanceof Table singleTable) || singleTable.getSchemaName() != null) {
            return List.of();
        }
        String table = singleTable.getName();
        if (table == null || table.isBlank()) {
            return List.of();
        }
        table = table.toLowerCase();

        Set<String> existingCols = loadExistingColumns(schema, table);
        if (existingCols == null) {
            return List.of(); // 元数据失败 → 降级放行
        }

        // 收集查询引用的列（select / where / having / group by / order by）
        ColumnCollector collector = new ColumnCollector(); // 内部直接收集 List<Column>
        visitExpressions(ps, select, collector);
        if (collector.skipStar) {
            return List.of(); // SELECT * 无法判列，跳过列校验
        }

        String tableAlias = singleTable.getAlias() == null ? table
                : singleTable.getAlias().getName().toLowerCase();

        Set<String> missing = new LinkedHashSet<>();
        for (net.sf.jsqlparser.schema.Column c : collector.getColumns()) {
            String colName = c.getColumnName();
            if (colName == null || colName.isBlank()) {
                continue;
            }
            colName = colName.toLowerCase();

            net.sf.jsqlparser.schema.Table t = c.getTable();
            String qualifier = t == null ? null : t.getName();
            if (qualifier == null) {
                // 裸列：单表场景归本表
                if (!existingCols.contains(colName)) {
                    missing.add(colName);
                }
            } else {
                String q = qualifier.toLowerCase();
                // 限定列：仅当限定符是本表或其别名时才校验；其它限定符（其它表/别名/CTE）不校验
                if ((q.equals(table) || q.equals(tableAlias)) && !existingCols.contains(colName)) {
                    missing.add(colName);
                }
            }
        }
        return new ArrayList<>(missing);
    }

    /** 对 select/where/having/groupBy/orderBy 的表达式应用访问者，收集引用的列。 */
    private void visitExpressions(PlainSelect ps, Select select,
                                  net.sf.jsqlparser.expression.ExpressionVisitorAdapter<Object> visitor) {
        for (net.sf.jsqlparser.statement.select.SelectItem<?> si : ps.getSelectItems()) {
            net.sf.jsqlparser.expression.Expression e = si.getExpression();
            if (e instanceof net.sf.jsqlparser.statement.select.AllColumns
                    || e instanceof net.sf.jsqlparser.statement.select.AllTableColumns) {
                // 出现 * 则跳过列校验；visitor 实际总是 ColumnCollector（单表场景），故可安全 cast
                ((ColumnCollector) visitor).setSkipStar();
                return;
            }
            e.accept(visitor, null);
        }
        if (ps.getWhere() != null) {
            ps.getWhere().accept(visitor, null);
        }
        if (ps.getHaving() != null) {
            ps.getHaving().accept(visitor, null);
        }
        if (ps.getGroupBy() != null && ps.getGroupBy().getGroupByExpressionList() != null) {
            for (Object o : ps.getGroupBy().getGroupByExpressionList()) {
                if (o instanceof net.sf.jsqlparser.expression.Expression ex) {
                    ex.accept(visitor, null);
                }
            }
        }
        if (select.getOrderByElements() != null) {
            for (net.sf.jsqlparser.statement.select.OrderByElement o : select.getOrderByElements()) {
                if (o != null && o.getExpression() != null) {
                    o.getExpression().accept(visitor, null);
                }
            }
        }
    }

    /** 查询某表的列名；失败返回 null（降级）。 */
    private Set<String> loadExistingColumns(String schema, String table) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = ? AND table_name = ?",
                    schema, table);
            Set<String> cols = new LinkedHashSet<>();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Object v = row.get("column_name");
                    if (v != null) {
                        cols.add(v.toString().toLowerCase());
                    }
                }
            }
            if (cols.isEmpty()) {
                // 无列元数据 → 视为不可用，降级跳过列校验（宁漏不误），避免空集合误拦所有列
                return null;
            }
            return cols;
        } catch (Exception e) {
            log.warn("查询表({})列名失败，跳过列校验：{}", table, e.getMessage());
            return null;
        }
    }

    private static final class ColumnCollector
            extends net.sf.jsqlparser.expression.ExpressionVisitorAdapter<Object> {
        private final List<net.sf.jsqlparser.schema.Column> columns = new ArrayList<>();
        private boolean skipStar;

        void setSkipStar() {
            this.skipStar = true;
        }

        List<net.sf.jsqlparser.schema.Column> getColumns() {
            return columns;
        }

        @Override
        public <S> Object visit(net.sf.jsqlparser.schema.Column column, S context) {
            if (!skipStar) {
                columns.add(column);
            }
            return null;
        }
    }

    /* ---------------- 错误文本 ---------------- */

    private String buildErrorText(List<String> missingTables, List<String> missingColumns,
                                  Set<String> existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("错误：SQL 引用了当前租户不存在的表/列，请修正后重试。");
        if (!missingTables.isEmpty()) {
            sb.append("\n缺失表：").append(joinMasked(missingTables));
            List<String> sorted = new ArrayList<>(existing);
            sorted.sort(Comparator.comparingInt(t -> editDistance(t, missingTables.get(0))));
            int cap = Math.min(MAX_CANDIDATES, sorted.size());
            List<String> candidates = new ArrayList<>(sorted.subList(0, cap));
            sb.append("\n可用表：").append(String.join("、", sorted));
            sb.append("\n近似候选：").append(candidates.stream()
                    .map(c -> c + "（距离 " + editDistance(c, missingTables.get(0)) + "）")
                    .collect(Collectors.joining("、")));
        }
        if (!missingColumns.isEmpty()) {
            sb.append("\n缺失列：").append(joinMasked(missingColumns));
        }
        return sb.toString();
    }

    private String joinMasked(List<String> names) {
        return names.stream().map(this::mask).collect(Collectors.joining("、"));
    }

    /** 敏感标识符脱敏为 [脱敏列]。 */
    private String mask(String name) {
        return SENSITIVE_NAMES.contains(name.toLowerCase()) ? "[脱敏列]" : name;
    }

    /** Levenshtein 编辑距离，用于近似候选排序（可用表/候选规模很小，性能足够）。 */
    static int editDistance(String a, String b) {
        String sa = a == null ? "" : a;
        String sb2 = b == null ? "" : b;
        int[] prev = new int[sb2.length() + 1];
        int[] curr = new int[sb2.length() + 1];
        for (int j = 0; j <= sb2.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= sa.length(); i++) {
            curr[0] = i;
            for (int j = 1; j <= sb2.length(); j++) {
                int cost = sa.charAt(i - 1) == sb2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            System.arraycopy(curr, 0, prev, 0, sb2.length() + 1);
        }
        return prev[sb2.length()];
    }
}