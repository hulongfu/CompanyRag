package com.company.rag.common.constant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link EvalRegressionReportDdl} 单元测试（spec §3.2.3）。
 *
 * <p>单一 DDL 源：建表 + 2 索引 + RLS + policy + grant 齐备；模板共 11 个 {@code %s}
 * 全等于 schemaName，单参 {@code String.format} 填充后 <b>不残留任何 {@code %} 占位符</b>，
 * 避免实现者误展开实参列表导致配对混乱。
 */
class EvalRegressionReportDdlTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:\\d+\\$)?s");

    @Test
    void build_containsCreateTableAndAllKeyParts() {
        String ddl = EvalRegressionReportDdl.build("tenant_a");
        assertTrue(ddl.contains("tenant_a.eval_regression_report"));
        assertTrue(ddl.contains("CREATE TABLE IF NOT EXISTS"));
        assertTrue(ddl.contains("idx_tenant_a_eval_rep_tenant_time"));
        assertTrue(ddl.contains("idx_tenant_a_eval_rep_tenant_fp"));
        assertTrue(ddl.contains("ENABLE ROW LEVEL SECURITY"));
        assertTrue(ddl.contains("FORCE ROW LEVEL SECURITY"));
        assertTrue(ddl.contains("tenant_isolation_eval_rep"));
        assertTrue(ddl.contains("current_tenant_id()"));
        // grant：两个表粒度（SELECT,INSERT + sequence），其余由 createTenantSchema blanket 兜底
        assertTrue(ddl.contains("GRANT SELECT, INSERT ON tenant_a.eval_regression_report"));
        assertTrue(ddl.contains("GRANT USAGE, SELECT ON SEQUENCE tenant_a.eval_regression_report_id_seq"));
    }

    @Test
    void build_rendersSchemaIntoSchemaQualifiedNames() {
        String ddl = EvalRegressionReportDdl.build("tenant_abc_123");
        assertTrue(ddl.contains("tenant_abc_123.eval_regression_report"));
        assertTrue(ddl.contains("idx_tenant_abc_123_eval_rep_tenant_time"));
        assertTrue(ddl.contains("idx_tenant_abc_123_eval_rep_tenant_fp"));
        assertFalse(ddl.contains("%s"));
    }

    @Test
    void build_noResidualPlaceholderForDifferentSchema() {
        String ddl = EvalRegressionReportDdl.build("tenant_x");
        assertFalse(ddl.contains("%s"));
        assertTrue(ddl.contains("tenant_x.eval_regression_report"));
    }

    @Test
    void build_placeholderCountMatchesTemplateFields() {
        // 模板共 11 个 %s，全部填充 schemaName
        String ddl = EvalRegressionReportDdl.build("tenant_a");
        Matcher m = PLACEHOLDER.matcher(ddl);
        int remaining = 0;
        while (m.find()) {
            remaining++;
        }
        assertEquals(0, remaining, "build() 后不应残留任何 %s 占位符");
    }
}
