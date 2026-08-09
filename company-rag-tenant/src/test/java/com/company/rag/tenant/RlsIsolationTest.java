package com.company.rag.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RLS（Row-Level Security）真实隔离测试
 * <p>
 * 验证 RLS 策略真正生效，确保：
 * 1. 设置 app.tenant_id 后只能访问对应租户的数据
 * 2. 未设置 app.tenant_id 时返回 0 行（安全失败）
 * 3. 越权 INSERT 被 WITH CHECK 拒绝
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RlsIsolationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String TEST_URL = "jdbc:postgresql://localhost:5432/company_rag";
    private static final String TEST_USER = "company_rag_app";
    private static final String TEST_PASSWORD = "company_rag_app_password_change_me";

    @BeforeEach
    void setUp() {
        // 清理测试数据（如果存在）
        try {
            jdbcTemplate.update("DELETE FROM tenant_default.rag_document WHERE file_name LIKE 'rls_test_%'");
        } catch (Exception e) {
            // 表可能不存在，忽略
        }
    }

    @AfterEach
    void tearDown() {
        // 清理测试数据
        try {
            jdbcTemplate.update("DELETE FROM tenant_default.rag_document WHERE file_name LIKE 'rls_test_%'");
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 测试 1：设置租户 1 的上下文，应能读到租户 1 的数据
     */
    @Test
    void testRlsWithTenant1_ShouldReturnData() throws SQLException {
        try (Connection conn = DriverManager.getConnection(TEST_URL, TEST_USER, TEST_PASSWORD)) {
            // 设置 search_path 到租户 1 的 schema
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO tenant_default, public");
                stmt.execute("SET app.tenant_id = 1");
            }

            // 插入测试数据（租户 1）
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO rag_document (tenant_id, file_name, file_type, file_size) VALUES (1, 'rls_test_tenant1', 'txt', 100)");
            }

            // 查询应返回 1 行
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM rag_document WHERE file_name LIKE 'rls_test_%'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "租户 1 应能读取自己的数据");
            }
        }
    }

    /**
     * 测试 2：设置租户 2 的上下文，应读不到租户 1 的数据（返回 0 行）
     */
    @Test
    void testRlsWithTenant2_ShouldNotReturnTenant1Data() throws SQLException {
        try (Connection conn = DriverManager.getConnection(TEST_URL, TEST_USER, TEST_PASSWORD)) {
            // 先以租户 1 身份插入数据
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO tenant_default, public");
                stmt.execute("SET app.tenant_id = 1");
                stmt.execute("INSERT INTO rag_document (tenant_id, file_name, file_type, file_size) VALUES (1, 'rls_test_tenant1', 'txt', 100)");
            }

            // 切换到租户 2 的上下文
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET app.tenant_id = 2");
            }

            // 查询应返回 0 行（RLS 拦截）
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM rag_document WHERE file_name LIKE 'rls_test_%'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "租户 2 不应读取租户 1 的数据");
            }
        }
    }

    /**
     * 测试 3：未设置 app.tenant_id（默认为 0），应返回 0 行（安全失败）
     */
    @Test
    void testRlsWithoutTenantId_ShouldReturnZeroRows() throws SQLException {
        try (Connection conn = DriverManager.getConnection(TEST_URL, TEST_USER, TEST_PASSWORD)) {
            // 先以租户 1 身份插入数据
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO tenant_default, public");
                stmt.execute("SET app.tenant_id = 1");
                stmt.execute("INSERT INTO rag_document (tenant_id, file_name, file_type, file_size) VALUES (1, 'rls_test_tenant1', 'txt', 100)");
            }

            // 重置 app.tenant_id 为 0（模拟未设置上下文）
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET app.tenant_id = 0");
            }

            // 查询应返回 0 行（安全失败）
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM rag_document WHERE file_name LIKE 'rls_test_%'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "未设置租户上下文时应返回 0 行");
            }
        }
    }

    /**
     * 测试 4：越权 INSERT（租户 2 尝试插入 tenant_id=1 的数据）应被 WITH CHECK 拒绝
     */
    @Test
    void testRlsWithCheck_ShouldRejectUnauthorizedInsert() throws SQLException {
        try (Connection conn = DriverManager.getConnection(TEST_URL, TEST_USER, TEST_PASSWORD)) {
            // 设置租户 2 的上下文
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO tenant_default, public");
                stmt.execute("SET app.tenant_id = 2");
            }

            // 尝试插入 tenant_id=1 的数据（越权）
            SQLException exception = assertThrows(
                SQLException.class,
                () -> {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("INSERT INTO rag_document (tenant_id, file_name, file_type, file_size) VALUES (1, 'rls_test_unauthorized', 'txt', 100)");
                    }
                },
                "越权 INSERT 应被 WITH CHECK 拒绝"
            );

            // 验证错误信息包含 RLS 相关提示
            assertTrue(
                exception.getMessage().contains("policy") || exception.getMessage().contains("RLS"),
                "错误信息应包含 RLS 策略相关提示：实际错误=" + exception.getMessage()
            );
        }
    }

    /**
     * 测试 5：正常 INSERT（租户 2 插入 tenant_id=2 的数据）应成功
     */
    @Test
    void testRlsWithCheck_ShouldAllowAuthorizedInsert() throws SQLException {
        try (Connection conn = DriverManager.getConnection(TEST_URL, TEST_USER, TEST_PASSWORD)) {
            // 设置租户 2 的上下文
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO tenant_default, public");
                stmt.execute("SET app.tenant_id = 2");
            }

            // 插入租户 2 的数据（应成功）
            try (Statement stmt = conn.createStatement()) {
                int rows = stmt.executeUpdate("INSERT INTO rag_document (tenant_id, file_name, file_type, file_size) VALUES (2, 'rls_test_tenant2', 'txt', 200)");
                assertEquals(1, rows, "应成功插入 1 行");
            }

            // 验证数据存在
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM rag_document WHERE file_name = 'rls_test_tenant2'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "应能读取刚插入的数据");
            }
        }
    }

    /**
     * 测试 6：vector_store 的 Schema 隔离测试
     * <p>
     * vector_store 表不使用 RLS，仅通过 Schema 隔离：
     * - 租户 1 的数据在 tenant_default.vector_store
     * - 租户 2 的数据在 tenant_acme.vector_store（假设有这个租户）
     * - 通过 search_path 路由到正确的 schema
     */
    @Test
    void testVectorStoreSchemaIsolation() throws SQLException {
        try (Connection conn = DriverManager.getConnection(TEST_URL, TEST_USER, TEST_PASSWORD)) {
            // 清理测试数据
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO tenant_default, public");
                stmt.execute("DELETE FROM vector_store WHERE content LIKE 'rls_test_vector_%'");
            }

            // 租户 1 在 tenant_default schema 插入向量
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO tenant_default, public");
                stmt.execute("INSERT INTO vector_store (id, content, embedding) VALUES ('a0000000-0000-0000-0000-000000000001', 'rls_test_vector_tenant1', '[0.1,0.2,0.3]')");
            }

            // 验证租户 1 能读取自己的向量
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM vector_store WHERE content LIKE 'rls_test_vector_%'")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "租户 1 应能读取自己的向量");
            }

            // 切换到另一个 schema（模拟租户 2）
            // 注意：由于我们没有创建 tenant_acme schema，这里测试 search_path 切换
            // 在真实环境中，租户 2 的 search_path 会指向 tenant_acme，看不到 tenant_default 的数据
            try (Statement stmt = conn.createStatement()) {
                // 切换到空的 schema（模拟其他租户）
                stmt.execute("SET search_path TO public");
                
                // 查询 vector_store 应该找不到数据（因为 tenant_default.vector_store 不在 search_path 中）
                // 注意：这里会报错说表不存在，这正是我们想要的——Schema 隔离
                SQLException exception = assertThrows(
                    SQLException.class,
                    () -> {
                        try (Statement queryStmt = conn.createStatement()) {
                            queryStmt.execute("SELECT COUNT(*) FROM vector_store WHERE content LIKE 'rls_test_vector_%'");
                        }
                    },
                    "切换到其他 schema 后应无法访问 vector_store 表"
                );
                
                assertTrue(
                    exception.getMessage().contains("does not exist") || exception.getMessage().contains("不存在"),
                    "错误信息应提示表不存在：" + exception.getMessage()
                );
            }
        }
    }
}
