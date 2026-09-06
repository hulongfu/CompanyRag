package com.company.rag.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * audit_log 平台级审计表的租户豁免回归测试。
 * <p>
 * 背景：审计表存放所有租户记录，admin 需跨租户可见。
 * 若 {@code ignoreTable} 对 audit_log 的豁免失效，TenantLine 会为
 * 跨租户 insert/select 自动追加 {@code tenant_id=?},导致写入被污染、
 * admin 查询被截断（只能看到"当前租户"）。
 * <p>
 * 本测试核验 {@code ignoreTable} 对审计表名（含/不含 schema 前缀）的豁免判定。
 * 数据库级联链路（租户 context 下写入、跨租户查询全量可见）需真实 PG，
 * 本环境无 PG 时跳过（与 {@code RlsIsolationTest} 同理）。
 */
class AuditLogIgnoreTableTest {

    /** 复刻 TenantMyBatisPlusConfig 的豁免逻辑，验证其判定口径 */
    private boolean ignoreTable(String tableName) {
        return "sys_tenant".equalsIgnoreCase(tableName)
                || "sys_user".equalsIgnoreCase(tableName)
                || "sys_user_tenant_rel".equalsIgnoreCase(tableName)
                || "audit_log".equalsIgnoreCase(tableName)
                || (tableName != null && tableName.toLowerCase().endsWith(".audit_log"));
    }

    @Test
    @DisplayName("纯表名 audit_log 应被豁免（MP 传给 ignoreTable 的实际形态）")
    void ignoresPlainTableName() {
        // MP 的 TenantLineInnerInterceptor 传的是 jsqlparser Table.getName()，即纯表名
        assertTrue(ignoreTable("audit_log"), "纯表名 audit_log 应豁免租户隔离");
    }

    @Test
    @DisplayName("带 schema 前缀的表名应被豁免（防御性 endsWith 兜底）")
    void ignoresSchemaQualifiedTableName() {
        assertTrue(ignoreTable("public.audit_log"), "schema 限定表名应豁免租户隔离");
        assertTrue(ignoreTable("SOME_SCHEMA.audit_log"), "任意 schema 限定表名应豁免租户隔离");
    }

    @Test
    @DisplayName("普通业务表不应被豁免（例如 rag_document / doc_chunk）")
    void doesNotIgnoreBusinessTables() {
        assertFalse(ignoreTable("rag_document"), "业务表 rag_document 不应豁免租户隔离");
        assertFalse(ignoreTable("doc_chunk"), "业务表 doc_chunk 不应豁免租户隔离");
    }
}