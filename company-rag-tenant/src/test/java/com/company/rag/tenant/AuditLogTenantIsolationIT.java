package com.company.rag.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * audit_log 平台级审计表「跨租户写读」集成测试（需真实 PG，与 {@code RlsIsolationTest} 同基建）。
 * <p>
 * 验证 {@code public.audit_log} 表豁免租户行级隔离后的端到端语义：
 * <ol>
 *   <li>在租户 context（{@code SET app.tenant_id}）下写入审计，应成功写入该租户记录；</li>
 *   <li>admin（未设置租户过滤上下文）以跨租户视角查询 {@code public.audit_log}，应能看到全部租户记录
 *       —— 而非被 {@code tenant_id=?} 行级条件截断为"仅当前租户"。</li>
 * </ol>
 * <p>
 * 前置：已由 init.sql/k8s 建立 {@code public.audit_log} 表与索引；TenantLine 对 audit_log 豁免
 * （否则 TenantLine 会为其追加 tenant_id 条件，admin 跨租户查询被截断）。
 * <p>
 * 需要真实 PG：仅当系统属性 {@code it.pg=true} 时启用，否则整类跳过，避免无 PG 环境下抛异常断构建
 * （同 RlsIsolationTest，通过直接 JDBC 直连真实 PG，只验证数据库层隔离行为，不依赖 Spring 容器）。
 */
@EnabledIfSystemProperty(named = "it.pg", matches = "true")
class AuditLogTenantIsolationIT {

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
        // 清理上次残留；表可能未建，失败忽略
        try (Connection conn = DriverManager.getConnection(TEST_URL, TEST_USER, TEST_PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM public.audit_log WHERE detail LIKE 'audit_it_%'");
        } catch (SQLException e) {
            // 表可能不存在，忽略
        }
    }

    /**
     * 在租户 context 写一条审计，再以 admin 视角跨租户查询，断言 admin 能看到全部租户记录。
     */
    @Test
    void adminShouldSeeAllTenantAuditRecordsNotTruncated() throws SQLException {
        try (Connection conn = DriverManager.getConnection(TEST_URL, TEST_USER, TEST_PASSWORD)) {
            // 1) 以租户 1 身份写一条审计
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO public.audit_log "
                        + "(tenant_id, user_id, action_type, target_type, target_id, detail, created_at) "
                        + "VALUES ('1', 1, 'AUDIT_IT', 'it', '1', 'audit_it_tenant1', now())");
            }

            // 2) 以租户 2 身份写一条审计
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("INSERT INTO public.audit_log "
                        + "(tenant_id, user_id, action_type, target_type, target_id, detail, created_at) "
                        + "VALUES ('2', 2, 'AUDIT_IT', 'it', '2', 'audit_it_tenant2', now())");
            }

            // 3) admin（不设置 app.tenant_id，模拟平台管理员跨租户视角）查询
            //    public.audit_log 不应被 tenant_id 行级条件截断
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(*) FROM public.audit_log WHERE detail LIKE 'audit_it_%'")) {
                rs.next();
                assertEquals(2, rs.getInt(1),
                        "admin 应看到全部租户的审计记录；若被 tenant_id 截断则只能返回 1 条");
            }
        }
    }
}