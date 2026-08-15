package com.company.rag.agent.tool;

import com.company.rag.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 测试 WHERE 子句中的跨租户访问漏洞
 */
class WhereSubqueryTest {

    private JdbcTemplate mockJdbcTemplate;
    private DatabaseQueryTool databaseQueryTool;

    @BeforeEach
    void setUp() {
        mockJdbcTemplate = mock(JdbcTemplate.class);
        databaseQueryTool = new DatabaseQueryTool(mockJdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void testWhereSubqueryWithQuotedSchema() {
        // 测试 WHERE 子句中的带引号跨租户访问
        // SQL: SELECT * FROM users WHERE id IN (SELECT user_id FROM "tenant_other"."orders")
        TenantContext.setSchema("tenant_123");
        
        Map<String, Object> params = Map.of(
            "sql", "SELECT * FROM users WHERE id IN (SELECT user_id FROM \"tenant_other\".\"orders\")"
        );
        String result = databaseQueryTool.execute(params);
        
        System.out.println("=== WHERE 子查询测试结果 ===");
        System.out.println("结果：" + result);
        
        // 应该拒绝跨租户访问
        assertTrue(result.contains("错误"), "应该返回错误，实际结果：" + result);
        assertTrue(result.contains("schema") || result.contains("禁止"), 
            "应该提示禁止显式指定 schema，实际结果：" + result);
    }

    @Test
    void testWhereSubqueryWithUnquotedSchema() {
        // 测试 WHERE 子句中的不带引号跨租户访问
        // SQL: SELECT * FROM users WHERE id IN (SELECT user_id FROM tenant_other.orders)
        TenantContext.setSchema("tenant_123");
        
        Map<String, Object> params = Map.of(
            "sql", "SELECT * FROM users WHERE id IN (SELECT user_id FROM tenant_other.orders)"
        );
        String result = databaseQueryTool.execute(params);
        
        System.out.println("=== WHERE 子查询（不带引号）测试结果 ===");
        System.out.println(result);
        
        // 应该拒绝跨租户访问
        assertTrue(result.contains("错误"), "应该返回错误");
        assertTrue(result.contains("schema") || result.contains("禁止"), 
            "应该提示禁止显式指定 schema");
    }

    @Test
    void testSelectSubqueryWithQuotedSchema() {
        // 测试 SELECT 列表中的带引号跨租户访问
        // SQL: SELECT id, (SELECT COUNT(*) FROM "tenant_other"."orders") FROM users
        TenantContext.setSchema("tenant_123");
        
        Map<String, Object> params = Map.of(
            "sql", "SELECT id, (SELECT COUNT(*) FROM \"tenant_other\".\"orders\") FROM users"
        );
        String result = databaseQueryTool.execute(params);
        
        System.out.println("=== SELECT 子查询测试结果 ===");
        System.out.println(result);
        
        // 应该拒绝跨租户访问
        assertTrue(result.contains("错误"), "应该返回错误");
        assertTrue(result.contains("schema") || result.contains("禁止"), 
            "应该提示禁止显式指定 schema");
    }

    @Test
    void testJoinSubqueryWithQuotedSchema() {
        // 测试 JOIN 子查询中的带引号跨租户访问
        // SQL: SELECT * FROM users JOIN (SELECT * FROM "tenant_other"."orders") o ON users.id = o.user_id
        TenantContext.setSchema("tenant_123");
        
        Map<String, Object> params = Map.of(
            "sql", "SELECT * FROM users JOIN (SELECT * FROM \"tenant_other\".\"orders\") o ON users.id = o.user_id"
        );
        String result = databaseQueryTool.execute(params);
        
        System.out.println("=== JOIN 子查询测试结果 ===");
        System.out.println(result);
        
        // 应该拒绝跨租户访问
        assertTrue(result.contains("错误"), "应该返回错误");
        assertTrue(result.contains("schema") || result.contains("禁止"), 
            "应该提示禁止显式指定 schema");
    }
}
