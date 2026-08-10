package com.company.rag.agent.tool;

import com.company.rag.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DatabaseQueryTool 测试
 * 验证租户隔离、表名白名单、SQL 重写等安全机制
 */
class DatabaseQueryToolTest {

    private JdbcTemplate mockJdbcTemplate;
    private DatabaseQueryTool databaseQueryTool;

    @BeforeEach
    void setUp() {
        mockJdbcTemplate = mock(JdbcTemplate.class);
        databaseQueryTool = new DatabaseQueryTool(mockJdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        // 清理租户上下文
        TenantContext.clear();
    }

    @Test
    void testGetNameAndDescription() {
        assertEquals("database_query", databaseQueryTool.getName());
        assertNotNull(databaseQueryTool.getDescription());
        assertTrue(databaseQueryTool.getDescription().contains("数据库"));
    }

    @Test
    void testExecuteWithEmptySql() {
        Map<String, Object> params = Map.of("sql", "");
        String result = databaseQueryTool.execute(params);
        
        assertTrue(result.contains("错误"));
        assertTrue(result.contains("不能为空"));
    }

    @Test
    void testExecuteWithNonSelectSql() {
        Map<String, Object> params = Map.of("sql", "DELETE FROM users");
        String result = databaseQueryTool.execute(params);
        
        assertTrue(result.contains("错误"));
        assertTrue(result.contains("仅支持 SELECT"));
    }

    @Test
    void testExecuteWithDangerousKeywords() {
        Map<String, Object> params = Map.of("sql", "SELECT * FROM users; DROP TABLE users");
        String result = databaseQueryTool.execute(params);
        
        assertTrue(result.contains("错误"));
        assertTrue(result.contains("包含禁止的操作"));
    }

    @Test
    void testExecuteWithoutTenantContext() {
        // 不设置租户上下文
        TenantContext.clear();
        
        Map<String, Object> params = Map.of("sql", "SELECT * FROM users");
        String result = databaseQueryTool.execute(params);
        
        assertTrue(result.contains("错误"));
        assertTrue(result.contains("未设置租户上下文"));
    }

    @Test
    void testExecuteWithTenantContext() {
        // 设置租户上下文
        TenantContext.setSchema("tenant_123");
        
        // Mock 查询结果
        List<Map<String, Object>> mockResult = List.of(
            Map.of("id", 1, "name", "测试用户")
        );
        when(mockJdbcTemplate.queryForList(anyString())).thenReturn(mockResult);
        
        Map<String, Object> params = Map.of("sql", "SELECT * FROM users");
        String result = databaseQueryTool.execute(params);
        
        // 验证 SQL 被重写，添加了 schema 前缀
        verify(mockJdbcTemplate).queryForList(argThat(sql -> 
            sql.contains("tenant_123.users") && sql.contains("LIMIT")
        ));
        
        assertNotNull(result);
        assertTrue(result.contains("查询结果"));
    }

    @Test
    void testExecuteWithTableWhitelist() {
        // 设置租户上下文
        TenantContext.setSchema("tenant_123");
        
        // Mock 查询结果
        when(mockJdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        
        // 测试白名单为空时允许查询所有表
        Map<String, Object> params = Map.of("sql", "SELECT * FROM users");
        String result = databaseQueryTool.execute(params);
        
        verify(mockJdbcTemplate).queryForList(anyString());
        
        // 注意：白名单配置通过@Value 从配置文件注入
        // 单元测试中默认白名单为空，允许所有表
        // 生产环境应通过配置 agent.database-query.allowed-tables 设置白名单
    }

    @Test
    void testExecuteWithJoinQuery() {
        // 设置租户上下文
        TenantContext.setSchema("tenant_123");
        
        // Mock 查询结果
        when(mockJdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        
        Map<String, Object> params = Map.of(
            "sql", "SELECT u.name, o.amount FROM users u JOIN orders o ON u.id = o.user_id"
        );
        String result = databaseQueryTool.execute(params);
        
        // 验证两个表都被添加了 schema 前缀
        verify(mockJdbcTemplate).queryForList(argThat(sql -> 
            sql.contains("tenant_123.users") && sql.contains("tenant_123.orders")
        ));
    }

    @Test
    void testExecuteWithCustomLimit() {
        // 设置租户上下文
        TenantContext.setSchema("tenant_123");
        
        when(mockJdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        
        Map<String, Object> params = Map.of(
            "sql", "SELECT * FROM users",
            "limit", 50
        );
        String result = databaseQueryTool.execute(params);
        
        verify(mockJdbcTemplate).queryForList(argThat(sql -> 
            sql.contains("LIMIT 50")
        ));
    }

    @Test
    void testExecuteWithExistingLimit() {
        // 设置租户上下文
        TenantContext.setSchema("tenant_123");
        
        when(mockJdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        
        // SQL 已有 LIMIT
        Map<String, Object> params = Map.of(
            "sql", "SELECT * FROM users LIMIT 10"
        );
        String result = databaseQueryTool.execute(params);
        
        // 不应该再添加 LIMIT
        verify(mockJdbcTemplate).queryForList(argThat(sql -> 
            !sql.contains("LIMIT 10 LIMIT")
        ));
    }

    @Test
    void testDescribeTableWithoutTenantContext() {
        TenantContext.clear();
        
        String result = databaseQueryTool.describeTable("users");
        
        assertTrue(result.contains("错误"));
        assertTrue(result.contains("未设置租户上下文"));
    }

    @Test
    void testDescribeTableWithTenantContext() {
        TenantContext.setSchema("tenant_123");
        
        when(mockJdbcTemplate.queryForList(
            anyString(), eq("tenant_123"), eq("users")
        )).thenReturn(List.of(
            Map.of("column_name", "id", "data_type", "bigint", "is_nullable", "NO")
        ));
        
        String result = databaseQueryTool.describeTable("users");
        
        verify(mockJdbcTemplate).queryForList(
            argThat(sql -> sql.contains("table_schema = ?")),
            eq("tenant_123"),
            eq("users")
        );
        
        assertTrue(result.contains("查询结果"));
    }

    @Test
    void testDescribeTableWithInvalidTableName() {
        TenantContext.setSchema("tenant_123");
        
        String result = databaseQueryTool.describeTable("users; DROP TABLE users");
        
        assertTrue(result.contains("错误"));
        assertTrue(result.contains("非法表名"));
    }

    @Test
    void testAddSchemaPrefixWithAlreadyQualifiedTable() {
        // 测试已经有 schema 前缀的表名不应该被重复添加
        TenantContext.setSchema("tenant_123");
        
        when(mockJdbcTemplate.queryForList(anyString())).thenReturn(List.of());
        
        // SQL 已经有 schema 前缀
        Map<String, Object> params = Map.of(
            "sql", "SELECT * FROM public.users"
        );
        String result = databaseQueryTool.execute(params);
        
        // 保持原有的 schema 不变
        verify(mockJdbcTemplate).queryForList(argThat(sql -> 
            sql.contains("public.users") && !sql.contains("tenant_123.public.users")
        ));
    }
}
