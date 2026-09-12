package com.company.rag.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

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
 * <p>
 *  * 需要真实 PG：仅当系统属性 {@code it.pg=true} 时启用，否则整类跳过，
 * 避免无 PG 环境下 {@code mvn test} 断构建。
 * <p>
 * 本测试通过直接 JDBC 直连真实 PG，只验证数据库层 RLS 行为，
 * 不依赖 Spring 容器（tenant 模块无 {@code @SpringBootConfiguration}，
 * 无法启动完整上下文），由 scripts/run-it.sh 在具备真实库的环境下触发。
 */
@EnabledIfSystemProperty(named = "it.pg", matches = "true")
class RlsIsolationTest {

    // 连接参数从环境变量读取（与 application.yml 同源），缺省匹配本地 docker PG，
    // 避免硬编码端口/密码与部署环境漂移导致集成测试在常规流程中被跳过。
    private static final String TEST_URL = "jdbc:postgresql://"
            + System.getenv().getOrDefault("POSTGRES_HOST", "localhost") + ":"
            + System.getenv().getOrDefault("POSTGRES_PORT", "5433") + "/"
            + System.getenv().getOrDefault("POSTGRES_DB", "company_rag");
    private static final String TEST_USER = System.getenv().getOrDefault("POSTGRES_USER", "company_rag_app");
    private static final String TEST_PASSWORD = System.getenv().getOrDefault("POSTGRES_PASSWORD", "company_rag_app123456");

    @BeforeEach
    void setUp() throws SQLException {
        // 清理测试数据。rag_document 启用了 FORCE RLS，直接在无上下文下 DELETE 会被拦截，
        // 因此按本套件涉及的每个租户显式 SET app.tenant_id 后再删除，避免跨运行时残留累积。
        try (Connection conn = DriverManager.getConnection(TEST_URL, TEST_USER, TEST_PASSWORD);
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            for (int tenantId : new int[]{1, 2}) {
                stmt.execute("SET LOCAL app.tenant_id = " + tenantId);
                stmt.executeUpdate("DELETE FROM tenant_default.rag_document WHERE file_name LIKE 'rls_test_%'");
            }
            conn.commit();
            stmt.execute("DELETE FROM tenant_default.vector_store WHERE content LIKE 'rls_test_vector_%'");
        } catch (SQLException e) {
            // 表可能不存在，忽略
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        // 清理测试数据（幂等，同上按租户删除）
        try (Connection conn = DriverManager.getConnection(TEST_URL, TEST_USER, TEST_PASSWORD);
             Statement stmt = conn.createStatement()) {
            conn.setAutoCommit(false);
            for (int tenantId : new int[]{1, 2}) {
                stmt.execute("SET LOCAL app.tenant_id = " + tenantId);
                stmt.executeUpdate("DELETE FROM tenant_default.rag_document WHERE file_name LIKE 'rls_test_%'");
            }
            conn.commit();
            stmt.execute("DELETE FROM tenant_default.vector_store WHERE content LIKE 'rls_test_vector_%'");
        } catch (SQLException e) {
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
     * vector_store 的隔离依赖 Schema 路由（每个租户独立 schema），而非行级过滤。
     * 验证：
     * - 在 tenant_default schema 中 vector_store 表存在
     * - 切换到 public schema 后，表不存在（报 schema 隔离错误）
     * <p>
     * 注意：真实运行环境下 vector_store 会被 FORCE RLS 且无 policy，任何 INSERT/DELETE 均被拦截，
     * 故本用例不做写操作，仅验证表的存在性与路由语义。
     */
    @Test
    void testVectorStoreSchemaIsolation() throws SQLException {
        try (Connection conn = DriverManager.getConnection(TEST_URL, TEST_USER, TEST_PASSWORD)) {
            // 1) tenant_default schema 中存在 vector_store 表
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO tenant_default, public");
                stmt.executeQuery("SELECT COUNT(*) FROM vector_store WHERE content LIKE 'rls_test_vector_%'");
            }

            // 2) 切换到 public schema（模拟其他租户的 search_path），vector_store 不应存在
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET search_path TO public");
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
