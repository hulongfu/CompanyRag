package com.company.rag.tenant.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.company.rag.common.service.AuditLogService;
import com.company.rag.tenant.mapper.TenantMapper;
import com.company.rag.tenant.mapper.UserMapper;
import com.company.rag.tenant.mapper.UserTenantRelMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 新建租户 schema 建表完整性的单元测试。
 *
 * 回归：评价功能依赖 answer_eval_result 表，但 createTenantSchema 仅建
 * rag_document/doc_chunk/vector_store/rag_session/rag_session_meta，
 * 运行时新建租户缺该表导致评估结果静默零落库。
 */
class TenantServiceImplSchemaTest {

    private TenantServiceImpl service;

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor 顺序：tenantMapper, userMapper, userTenantRelMapper, jdbcTemplate, auditLogService
        service = new TenantServiceImpl(
                mock(TenantMapper.class),
                mock(UserMapper.class),
                mock(UserTenantRelMapper.class),
                mock(JdbcTemplate.class),
                mock(AuditLogService.class));
    }

    @Test
    void createTableSql_containsAnswerEvalResultTable() {
        String sql = service.buildCreateTableSql("tenant_abc");
        assertTrue(sql.contains("answer_eval_result"));
    }

    @Test
    void createTableSql_answerEvalResultHasRlsPolicyAndSequenceGrant() {
        String sql = service.buildCreateTableSql("tenant_abc");
        // 新建租户的评估结果必须同样受 RLS 租户隔离保护，且 app 账号可写
        assertTrue(sql.contains("tenant_isolation_answer_eval"));
        assertTrue(sql.contains("answer_eval_result_id_seq"));
    }

    @Test
    void createTableSql_completesFormattingWithoutMissingArgs() {
        // 若新增表后未同步占位符，.formatted(schemaName, ...) 会抛 MissingFormatArgumentException
        String sql = service.buildCreateTableSql("tenant_abc");
        assertTrue(sql.contains("CREATE SCHEMA") || sql.contains("rag_document"));
        assertTrue(sql.contains("tenant_abc"));
    }

    @Test
    void createIndexSql_containsAnswerEvalIndex() {
        String sql = service.buildCreateIndexSql("tenant_abc");
        assertTrue(sql.contains("answer_eval_result"));
        assertTrue(sql.contains("answer_eval_tenant_time"));
    }

    @Test
    void createIndexSql_completesFormattingWithoutMissingArgs() {
        String sql = service.buildCreateIndexSql("tenant_abc");
        assertTrue(sql.contains("tenant_abc"));
    }

    @Test
    void createTableSql_containsToolApprovalTableWithRlsAndGrant() {
        String sql = service.buildCreateTableSql("tenant_abc");
        assertTrue(sql.contains("tool_approval_request"));
        assertTrue(sql.contains("tenant_isolation_tool_approval"));
        assertTrue(sql.contains("tool_approval_request_id_seq"));
        // 占位符全部替换，不含裸 %s（若新增表后未同步实参，formatted 早已抛异常，
        // 此处再强制验证已格式化的 SQL 无残留格式化占位符）
        assertTrue(!sql.contains("%s"));
    }

    @Test
    void createIndexSql_containsToolApprovalIndexes() {
        String sql = service.buildCreateIndexSql("tenant_abc");
        assertTrue(sql.contains("tool_approval_status"));
        assertTrue(sql.contains("tool_approval_time"));
        assertTrue(sql.contains("tool_approval_request"));
        assertTrue(!sql.contains("%s"));
    }

    @Test
    void normalizeSchemaName_plainCodeKeepsLowercasePrefix() {
        // 正常全小写 tenantCode：归一化后与原拼接一致
        assertEquals("tenant_test_company", service.normalizeSchemaName("test_company"));
    }

    @Test
    void normalizeSchemaName_upperMixedCodeLowercasedToMatchPgFold() {
        // 回归：tenantCode='HufuInSky' 时，若不转小写，schema_name 存入
        // 'tenant_HufuInSky'，而 CREATE SCHEMA 被折叠成 'tenant_hufuinsky'，二者失配，
        // 导致收敛器反查 public.sys_tenant 匹配不到而 WARN 跳过。
        assertEquals("tenant_hufuinsky", service.normalizeSchemaName("HufuInSky"));
    }

    @Test
    void normalizeSchemaName_allUpperCodeLowercased() {
        assertEquals("tenant_abc", service.normalizeSchemaName("ABC"));
    }
}