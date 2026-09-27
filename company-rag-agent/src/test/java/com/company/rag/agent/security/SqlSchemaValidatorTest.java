package com.company.rag.agent.security;

import com.company.rag.agent.config.Nl2sqlSchemaValidationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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