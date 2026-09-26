# nl2sql 表/列名自校验实现计划

> 日期：2026-09-26
> 关联 spec：`docs/superpowers/specs/2026-09-14-nl2sql-tool-hardening-design.md`
> 目标模块：`company-rag-agent`（仅此模块，无跨模块改动）

## 目标

在 `DatabaseQueryTool.queryDatabase()` 的既有安全链中，于「显式 schema 检查（第三道防线）」与「自动加租户 schema 前缀」之间，插入一步**表/列名自校验**：当 LLM 生成的 SQL 引用当前租户 schema 中不存在的表或列时，直接返回带缺失清单、可用表清单与编辑距离候选的错误文本，让 ReAct 自愈，而不是把 PG 原始报错回传给 LLM。

设计约束（来自 spec，均已批准）：
- **宁漏不误**：校验器自身任何异常一律降级放行，绝不让加固引入新失败模式。
- **先窄后宽**：表名全量校验；列仅「单表、无子查询、无 CTE」时校验裸列，多表只校验可映射的限定列，`SELECT *`、函数返回值、别名、CTE、系统表一律跳过。
- **不引入 `ToolResult`**：失败直接返回既有 `"错误：..."` String，缺失清单内联。
- **可热关断**：新增 `agent.nl2sql.schema-validation.enabled`，默认 `true`。
- 校验失败提前 return 时不落审计（与既有语法/危险词失败路径一致）。

## 环境事实与 jsqlparser API（均已实测）

- jsqlparser 5.0 jar：`~/.m2/repository/com/github/jsqlparser/jsqlparser/5.0/jsqlparser-5.0.jar`；agent pom 已声明同版本依赖，无需新增。
- `SqlSecurityValidator.validateSelectSql()`（`agent/security/`，224 行）**会拒绝任何带显式 schema 的表**（`validateFromItem` 中 schemaName 非 null 即抛 BizException）。因此到达表/列校验的 SQL 里所有表都是**裸表名**（无 schema 前缀、非系统表）。校验器内对显式 schema 的「跳过」是纯防御性冗余，仅用于独立单测。
- `SqlSecurityValidator` **没有** `parseOrThrow`，它只有 `validateSelectSql(String)` 与 `extractTableNames(String)` 两个 public static。SqlSchemaValidator 自行 `CCJSqlParserUtil.parse` 即可，**不依赖** SqlSecurityValidator 内部方法。
- `DatabaseQueryTool` 是 `@Component`（L40），单参构造 `DatabaseQueryTool(JdbcTemplate)`（L73），旧测试直接 `new DatabaseQueryTool(mockJdbcTemplate)`。→ 用**可选 setter 注入**，不改构造签名。
- 裸列 `Column.getTable()` 返回 **null**（实测）；含 CTE 时 `SqlSecurityValidator.extractTableNames` 会把 CTE 名当表名（实测）。→ 见 Task 3 规避。

实测确认的 JSqlParser 5.0 方法（勿偏离）：
`Select.getWithItemsList():List<WithItem>`、`WithItem.getAlias().getName()`、`Select.getPlainSelect()`、`Select.getOrderByElements()`、`PlainSelect.getFromItem()/getJoins()/getSelectItems()/getWhere()/getHaving()/getGroupBy()`、`GroupByElement.getGroupByExpressionList().getExpressions()`（元素是 `Object` 需 cast `Expression`）、`SelectItem.getExpression()`、`Column.getTable()/getColumnName()`、`Table.getName()/getSchemaName()/getAlias()`、`ExpressionVisitorAdapter<S>` + `override <S> Object visit(Column,S)`、`AllColumns`/`AllTableColumns`。

---

## 改动清单

| 文件 | 动作 |
|---|---|
| `company-rag-agent/.../agent/config/Nl2sqlSchemaValidationProperties.java` | 新增 |
| `company-rag-agent/.../agent/security/SqlSchemaValidator.java` | 新增 |
| `company-rag-agent/.../agent/tool/DatabaseQueryTool.java` | 修改（可选 setter + 插入校验点 + @Tool 提示）|
| `company-rag-bootstrap/src/main/resources/application.yml` | 修改（新增配置，默认启用）|
| `company-rag-agent/src/test/.../security/SqlSchemaValidatorTest.java` | 新增 |
| `company-rag-agent/src/test/.../tool/DatabaseQueryToolTest.java` | 修改（新增集成校验用例）|

---

## 逐步实现（TDD，先红后绿）

### Task 1 — 配置类 `Nl2sqlSchemaValidationProperties`

文件：`company-rag-agent/src/main/java/com/company/rag/agent/config/Nl2sqlSchemaValidationProperties.java`

```java
package com.company.rag.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * nl2sql 表/列名自校验配置。
 * <p>开启后，DatabaseQueryTool 会在执行前校验 LLM 生成的 SQL 所引用的表/列
 * 是否存在于当前租户 schema，缺失时返回带候选的错误文本，帮助 ReAct 自愈。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.nl2sql.schema-validation")
public class Nl2sqlSchemaValidationProperties {

    /** 是否启用表/列名自校验，默认 true；需热关断时改环境变量即可 */
    private boolean enabled = true;
}
```

与既有 `ApprovalProperties`（`@Data @Component @ConfigurationProperties(prefix = "agent.approval")`）完全同风格。

---

### Task 2 — 先写失败测试 `SqlSchemaValidatorTest`（红）

文件：`company-rag-agent/src/test/java/com/company/rag/agent/security/SqlSchemaValidatorTest.java`

用 mock `JdbcTemplate` 供 `information_schema` 行。构造注 `new SqlSchemaValidator(jdbcTemplate, props)`。

```java
package com.company.rag.agent.security;

import com.company.rag.agent.config.Nl2sqlSchemaValidationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqlSchemaValidatorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private Nl2sqlSchemaValidationProperties props;
    private SqlSchemaValidator validator;

    private static final String SCHEMA = "tenant_1";
    private static final List<String> EXISTING_TABLES = List.of("rag_document", "doc_chunk");
    private static final List<String> DOC_COLUMNS = List.of("id", "title", "content", "tenant_id");

    @BeforeEach
    void setUp() {
        props = new Nl2sqlSchemaValidationProperties();
        validator = new SqlSchemaValidator(jdbcTemplate, props);
        stubTables(EXISTING_TABLES);
    }

    private void stubTables(List<String> tables) {
        when(jdbcTemplate.queryForList(anyString(), eq(SCHEMA)))
                .thenReturn(tables.stream()
                        .map(n -> Map.<String, Object>of("table_name", n)).toList());
    }

    private void stubColumns(String table, List<String> cols) {
        when(jdbcTemplate.queryForList(anyString(), eq(SCHEMA), eq(table)))
                .thenReturn(cols.stream()
                        .map(n -> Map.<String, Object>of("column_name", n)).toList());
    }

    /* ---------- 表校验 ---------- */

    @Test
    void validExistingTable_passes() {
        assertNull(validator.validate("SELECT title FROM rag_document", SCHEMA));
    }

    @Test
    void noFromClause_skips() {
        assertNull(validator.validate("SELECT 1", SCHEMA));
    }

    @Test
    void unknownTable_returnsMissingList() {
        String r = validator.validate("SELECT * FROM typo_document", SCHEMA);
        assertNotNull(r);
        assertTrue(r.contains("错误"));
        assertTrue(r.contains("typo_document"));
    }

    @Test
    void unknownTable_includesAvailableAndCandidates() {
        String r = validator.validate("SELECT * FROM rag_documentt", SCHEMA);
        assertNotNull(r);
        assertTrue(r.contains("可用表"));
        assertTrue(r.contains("rag_document"));
        assertTrue(r.contains("近似候选"));
    }

    @Test
    void cteTable_isNotTreatedAsMissing() {
        assertNull(validator.validate(
                "WITH ten AS (SELECT id FROM rag_document) SELECT id FROM ten", SCHEMA));
    }

    @Test
    void explicitSchemaTable_isSkipped() {
        assertNull(validator.validate("SELECT * FROM pg_catalog.pg_tables", SCHEMA));
    }

    /* ---------- 列校验（单表窄范围） ---------- */

    @Test
    void singleTable_validBareColumn_passes() {
        stubColumns("rag_document", DOC_COLUMNS);
        assertNull(validator.validate(
                "SELECT title FROM rag_document WHERE tenant_id = 1", SCHEMA));
    }

    @Test
    void singleTable_unknownColumn_returnsMissing() {
        stubColumns("rag_document", DOC_COLUMNS);
        String r = validator.validate("SELECT titlee FROM rag_document", SCHEMA);
        assertNotNull(r);
        assertTrue(r.contains("titlee"));
    }

    @Test
    void singleTable_unknownWhereColumn_returnsMissing() {
        stubColumns("rag_document", DOC_COLUMNS);
        String r = validator.validate(
                "SELECT title FROM rag_document WHERE missing_col = 1", SCHEMA);
        assertNotNull(r);
        assertTrue(r.contains("missing_col"));
    }

    /* ---------- 跳过场景（宁漏不误） ---------- */

    @Test
    void starSelect_skipsColumnCheck() {
        assertNull(validator.validate("SELECT * FROM rag_document", SCHEMA));
    }

    @Test
    void aliasColumn_isSkipped() {
        stubColumns("rag_document", DOC_COLUMNS);
        assertNull(validator.validate("SELECT title AS t FROM rag_document", SCHEMA));
    }

    @Test
    void functionArg_isNotTreatedAsColumn() {
        assertNull(validator.validate("SELECT count(1) FROM rag_document", SCHEMA));
    }

    @Test
    void subquery_skipsColumnCheck() {
        assertNull(validator.validate(
                "SELECT id FROM (SELECT id FROM rag_document) sub", SCHEMA));
    }

    @Test
    void multitable_unqualifiedBareColumn_isSkipped() {
        stubColumns("rag_document", DOC_COLUMNS);
        stubColumns("doc_chunk", List.of("chunk_id"));
        assertNull(validator.validate(
                "SELECT id FROM rag_document JOIN doc_chunk ON rag_document.id = doc_chunk.chunk_id",
                SCHEMA));
    }

    /* ---------- 降级放行 ---------- */

    @Test
    void metadataException_degradesAndPasses() {
        when(jdbcTemplate.queryForList(anyString(), eq(SCHEMA)))
                .thenThrow(new RuntimeException("db down"));
        assertNull(validator.validate("SELECT title FROM rag_document", SCHEMA));
    }

    @Test
    void disabled_skipsAllValidation() {
        props.setEnabled(false);
        assertNull(validator.validate("SELECT * FROM typo_document", SCHEMA));
    }

    @Test
    void blankSql_returnsNull() {
        assertNull(validator.validate("", SCHEMA));
        assertNull(validator.validate(null, SCHEMA));
    }

    /* ---------- 敏感列脱敏 ---------- */

    @Test
    void sensitiveColumn_appearsAsMasked_notRaw() {
        props = new Nl2sqlSchemaValidationProperties();
        validator = new SqlSchemaValidator(jdbcTemplate, props);
        stubTables(List.of("users"));
        stubColumns("users", List.of("id", "password"));
        String r = validator.validate("SELECT passwd FROM users", SCHEMA);
        assertNotNull(r);
        assertFalse(r.contains("passwd"), "缺失列名不应原样回显");
        assertTrue(r.contains("[脱敏列]"), "敏感列应以 [脱敏列] 屏蔽");
    }
}
```

红验证（此时 `SqlSchemaValidator` 不存在，编译失败即红）：
```bash
cd /d/tmp/CompanyRag && mvn -o -pl company-rag-agent test -Dtest=SqlSchemaValidatorTest -DfailIfNoTests=false
```

---

### Task 3 — 实现 `SqlSchemaValidator` 主流程与表校验（绿）

文件：`company-rag-agent/src/main/java/com/company/rag/agent/security/SqlSchemaValidator.java`

```java
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
}
```

> 说明：`collectTables` 的 `item instanceof Select` 分支覆盖 `ParenthesedSelect`（派生表/子查询），其自身别名不是 `Table` 节点，天然不会进入 out；避免把派生表别名当表名。
> `loadExistingTables` 用 `information_schema` 且带 `table_schema = ?` 占位参数——不依赖 RLS（`information_schema` 不受 RLS 约束），必须显式带 schema 条件，杜绝跨租户元数据泄露（对齐 spec §5）。

---

### Task 4 — 列校验（单表窄范围）

`SqlSchemaValidator.java` 追加：

```java
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
            if (e instanceof net.sf.jsqlparser.expression.AllColumns
                    || e instanceof net.sf.jsqlparser.expression.AllTableColumns) {
                visitor.setSkipStar(); // 出现 * 则跳过列校验（版见下方说明）
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
```

> **说明**：`visitExpressions` 里遇到 `SELECT *`（`AllColumns`/`AllTableColumns`）时，置 `skipStar=true` 让后续不再收集列，从而整段列校验返回空（跳过）。下面 `checkColumns` 在 `visitExpressions` 后根据 `collector.skipStar` 短路返回空列表：
> ```java
> visitExpressions(ps, select, collector);
> if (collector.skipStar) {
>     return List.of(); // SELECT * 无法判列，跳过列校验
> }
> ```
> 注意：「`t.*`（AllTableColumns）」的限定表名本身不在此校验——多表/限定场景本来就整体跳过或只看可解析限定列，`t.*` 直接判为跳过列校验，最稳妥。

把上面的 `if (collector.skipStar) return List.of();` 放在 `visitExpressions(...)` 调用之后、取 `tableAlias` 之前。

---

### Task 5 — 错误文本、可用表清单与编辑距离候选

`SqlSchemaValidator.java` 追加：

```java
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
```

> 说明：`近似候选` 取「与缺失表中第一个表（`missingTables.get(0)`）编辑距离最小的前 3 个可用表」。多个缺失表时简化只对首个做候选——符合「先窄后宽、最小改动」。若需全量，可扩展为对所有缺失表求候选；本版不这样做。

---

### Task 6 — 修改 `DatabaseQueryTool`：可选注入 + 插入校验点 + @Tool 提示

文件：`company-rag-agent/src/main/java/com/company/rag/agent/tool/DatabaseQueryTool.java`

**（1）新增可选字段 + setter（字段区，L43 附近）：**
```java
    /** 可选；为 null 时跳过表/列名校验（兼容既有直接 new 的单测） */
    private SqlSchemaValidator schemaValidator;

    @Autowired
    public void setSchemaValidator(SqlSchemaValidator schemaValidator) {
        this.schemaValidator = schemaValidator;
    }
```
> 需在文件顶部 import `com.company.rag.agent.security.SqlSchemaValidator`。`@Autowired` 已在文件内 import（构造器 `@Autowired` 存在）。

**（2）插入校验点（当前 L81 `String qualifiedSql = addSchemaPrefix(...)` 之前，`containsExplicitSchema` 块之后）：**
```java
        // ✅ 第四道防线：nl2sql 表/列名自校验（缺失时让 ReAct 自愈）
        if (schemaValidator != null) {
            String validationError = schemaValidator.validate(cleanSql, currentSchema);
            if (validationError != null) {
                return validationError;
            }
        }

        // 自动添加当前租户 schema 前缀（使用移除注释后的 SQL）
        String qualifiedSql = addSchemaPrefix(cleanSql, currentSchema);
```
> 校验失败直接 return，与既有语法/危险词失败路径一致 → 不落审计（对齐 spec §3.5 / §6 尾注）。错误文本已含「可用表 + 近似候选」，工具侧不做二次查表。

**（3）@Tool 描述补充提示（L123-135 block 内追加一段）：**
```java
            不适用场景：
            - 查询用户、租户、审计日志等平台内部信息 -> 不可用
            - 知识库文档问答 -> 使用 searchKnowledgeBase
            
            若返回"缺失表"/"缺失列"，请参考其中的"可用表"与"近似候选"清单，修正 SQL 的表名/列名后重试。
            """
```

---

### Task 7 — 断言复核

运行（应全绿）：
```bash
cd /d/tmp/CompanyRag && mvn -o -pl company-rag-agent test -Dtest=SqlSchemaValidatorTest -DfailIfNoTests=false
```
若个别断言与实现不符，以 spec「宁漏不误 + 先窄后宽」为准则调整**测试或实现**，但绝不允许收窄为「误拦合法 SQL」。

---

### Task 8 — `DatabaseQueryToolTest` 新增集成用例 + 回归

`company-rag-agent/src/test/java/com/company/rag/agent/tool/DatabaseQueryToolTest.java` 追加：

```java
    @Test
    void noValidatorInjected_stillExecutes() {
        // 默认未注入 validator（构造单参）→ 跳过校验，走既有成功路径
        stubExecuteResult(List.of(Map.of("title", "d")));
        setTenantContext(); // 复用既有租户设置辅助；若测试类已有则沿用
        String r = databaseQueryTool.execute(Map.of("sql", "SELECT * FROM rag_document"));
        assertFalse(r, r, r.contains("缺失表") ? "不应触发校验" : "") /* 占位，见下方说明 */;
        assertNotNull(r);
    }

    @Test
    void injectedValidator_unknownTable_returnsError() {
        SqlSchemaValidator sv = new SqlSchemaValidator(
                mockJdbcTemplate, new Nl2sqlSchemaValidationProperties());
        ReflectionTestUtils.setField(databaseQueryTool, "schemaValidator", sv);
        when(mockJdbcTemplate.queryForList(anyString(), eq("tenant_1")))
                .thenReturn(List.of(Map.<String, Object>of("table_name", "rag_document")));
        stubExecuteResult(List.of());
        String r = databaseQueryTool.execute(Map.of("sql", "SELECT * FROM typo_document"));
        assertTrue(r.contains("缺失表"));
        assertTrue(r.contains("typo_document"));
    }
```
> **说明**：`noValidatorInjected_stillExecutes` 中 `assertFalse` 写法是笔误示意，真实应为简单的 `assertNotNull(r)` + `assertTrue(r.contains("标题"))`（复用既有成功期望）。实现时参照测试类既有 `stubExecuteResult` 成功断言的写法即可，无需引入「缺失表」断言（因为未注入 validator 本就不该触发）。
> `injectedValidator_unknownTable_returnsError`：`mockJdbcTemplate` 同时服务「表元数据查询 `queryForList(sql, schema)`」与「执行查询 `execute(ConnectionCallback)`」——两者方法不同、stub 互不冲突。校验发现缺失表后提前 return，不会真正执行查询，故 `stubExecuteResult(List.of())` 仅兜底。
> 注意育人项：`queryForList(anyString(), eq("tenant_1"))` 需与 `TenantContext.getSchema()` 实际值一致——测试需先 `TenantContext.setSchema("tenant_1")` 一次性设定。请确认该类既有租户上下文辅助方法返回的值就是 `tenant_1`，否则改用对应 schema 字符串。

运行全模块相关测试确认无回归：
```bash
cd /d/tmp/CompanyRag && mvn -o -pl company-rag-agent test -Dtest=SqlSchemaValidatorTest,DatabaseQueryToolTest -DfailIfNoTests=false
```

---

### Task 9 — 配置默认启用

`company-rag-bootstrap/src/main/resources/application.yml` 的 `agent:` 节点下新增：

```yaml
agent:
  # Python 可执行文件路径 ...（既有）
  nl2sql:
    schema-validation:
      enabled: ${NL2SQL_SCHEMA_VALIDATION_ENABLED:true}
```
> 支持环境变量覆盖；默认 `true`（与配置类默认一致）。

---

### Task 10 — 最终验证（仅 agent 模块）

```bash
cd /d/tmp/CompanyRag && mvn -o -pl company-rag-agent test -DfailIfNoTests=false
```
禁止运行全仓/多模块测试（系统验证范围铁律高于技能措辞）。

---

## 风险与护栏（对齐 spec §8）

| 风险 | 缓解 |
|---|---|
| CTE 名被当表名 → 误拦 | `checkTables` 显式收集并剔除 CTE 名（`getWithItemsList().getAlias().getName()`）|
| 裸列 `Column.getTable()` 为 null → NPE | 判空后按单表归表（Task 4）|
| `SELECT *` / 函数 / 别名 / 多表裸列误判 | `AllColumns`/`AllTableColumns` 判空、函数参数不产出 Column、别名不被收集、多表跳过（Task 4）|
| 元数据查询失败 → 阻断查询 | `loadExistingTables`/`loadExistingColumns` 捕获异常返回 null → `validate` 降级放行（Task 3/4）|
| 含 CTE/子查询/多表时列误判 | 前置条件短路仅留单表场景（Task 4）|
| 敏感列名回显泄露表结构 | `mask()` → `[脱敏列]`（Task 5）|
| 配置读取异常 | `isEnabled()` 默认启用（Task 3）|

## 不动项

- 不改 `DatabaseQueryTool` 构造签名（保留 `DatabaseQueryTool(JdbcTemplate)`），避开旧 3 测试回归（`DatabaseQueryToolTest`/`WhereSubqueryTest`/`ToolAuditTest`）。
- 不引入 `ToolResult`/`WarningItem`/`AgentResult.warnings`；校验失败不落审计。
- 不改 `SqlSecurityValidator`（`validateSelectSql`/`extractTableNames` 均不动，SqlSchemaValidator 自管解析）。
- 不加元数据缓存（spec §8 明确本版不缓存）。