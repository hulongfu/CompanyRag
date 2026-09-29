package com.company.rag.common.constant;

/**
 * 回归评估报告表 DDL 单一来源（spec §3.2.3）。
 *
 * <p>只承载 {@code eval_regression_report} 一表（建表 + 2 索引 + RLS + policy + grant），
 * 不混入 rag_session 补丁（feedback 列等由 TenantServiceImpl/迁移 runner 各自负责）。
 *
 * <p>模板内部共 11 个 {@code %1$s} 占位，<b>全部等于同一 schemaName</b>，
 * 故 {@link #build(String)} 用单参 {@code String.format(template, schemaName)}
 * 以索引式占位 {@code %1$s} 将同一参数填充全部位置，切勿展开成多参
 * （普通 {@code %1$s} 单参只会填充第一个，其余仍残留导致 MissingFormatArgumentException）。
 *
 * <p>统一走 {@code current_tenant_id()}（与 answer_eval_result / rag_session 同款 RLS），
 * 保证 app 账号连库时对跨租户行不可见。
 */
public final class EvalRegressionReportDdl {

    private EvalRegressionReportDdl() {
        // 工具类，禁止实例化
    }

    private static final String TEMPLATE = """
        CREATE TABLE IF NOT EXISTS %1$s.eval_regression_report (
            id BIGSERIAL PRIMARY KEY,
            tenant_id BIGINT NOT NULL,
            run_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            rule_version VARCHAR(64) NOT NULL,
            dataset_fingerprint VARCHAR(64),
            dataset_from TIMESTAMP,
            dataset_to TIMESTAMP,
            sample_count INT NOT NULL,
            pass_rate DOUBLE PRECISION NOT NULL,
            avg_score DOUBLE PRECISION NOT NULL,
            avg_relevancy DOUBLE PRECISION NOT NULL DEFAULT 0,
            avg_correctness DOUBLE PRECISION NOT NULL DEFAULT 0,
            avg_faithfulness DOUBLE PRECISION NOT NULL DEFAULT 0,
            persisted_pass_agree DOUBLE PRECISION NOT NULL DEFAULT 0,
            tp INT NOT NULL DEFAULT 0,
            tn INT NOT NULL DEFAULT 0,
            fp INT NOT NULL DEFAULT 0,
            fn INT NOT NULL DEFAULT 0,
            accuracy DOUBLE PRECISION NOT NULL DEFAULT 0,
            precision DOUBLE PRECISION NOT NULL DEFAULT 0,
            recall DOUBLE PRECISION NOT NULL DEFAULT 0,
            f1 DOUBLE PRECISION NOT NULL DEFAULT 0,
            negative_recall DOUBLE PRECISION NOT NULL DEFAULT 0,
            relevancy_agree DOUBLE PRECISION NOT NULL DEFAULT 0,
            correctness_agree DOUBLE PRECISION NOT NULL DEFAULT 0,
            faithfulness_agree DOUBLE PRECISION NOT NULL DEFAULT 0
        );
        CREATE INDEX IF NOT EXISTS idx_%1$s_eval_rep_tenant_time
            ON %1$s.eval_regression_report (tenant_id, run_time DESC);
        CREATE INDEX IF NOT EXISTS idx_%1$s_eval_rep_tenant_fp
            ON %1$s.eval_regression_report (tenant_id, dataset_fingerprint);
        ALTER TABLE %1$s.eval_regression_report ENABLE ROW LEVEL SECURITY;
        ALTER TABLE %1$s.eval_regression_report FORCE ROW LEVEL SECURITY;
        DROP POLICY IF EXISTS tenant_isolation_eval_rep ON %1$s.eval_regression_report;
        CREATE POLICY tenant_isolation_eval_rep ON %1$s.eval_regression_report
            FOR ALL TO company_rag_app
            USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
        GRANT SELECT, INSERT ON %1$s.eval_regression_report TO company_rag_app;
        GRANT USAGE, SELECT ON SEQUENCE %1$s.eval_regression_report_id_seq TO company_rag_app;
        """;

    /**
     * 生成指定租户 schema 下 {@code eval_regression_report} 表的完整 DDL。
     *
     * @param schemaName 目标 schema（须已通过正则白名单校验后才可调用）
     * @return 可直接经 {@code jdbcTemplate.execute(...)} 执行的 DDL 文本
     */
    public static String build(String schemaName) {
        return String.format(TEMPLATE, schemaName);
    }
}