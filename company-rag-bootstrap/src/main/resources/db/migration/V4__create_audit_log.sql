-- ============================================================
-- 存档脚本：audit_log 建表（不执行）
-- ------------------------------------------------------------
-- 【重要】本文件仅作存档用途，**不会被 Flyway 执行**：
--   CompanyRagApplication 排除了 FlywayAutoConfiguration，
--   db/migration/V*.sql 全部不执行。
-- 实际建表由以下两处完成（与 sql/init.sql、k8s/initdb-configmap.yaml 对齐）：
--   1. docker-compose: sql/init.sql（postgres 首启 initdb 执行）
--   2. K8s:            k8s/initdb-configmap.yaml（/docker-entrypoint-initdb.d/）
-- 既有库如需升级，请手工在 PG 执行下方 DDL（含索引）。
-- ============================================================

-- 审计日志表（平台级，tenant_id NOT NULL，登录取 SecurityUser 首个租户）
-- 存放所有租户的审计记录，显式存储于 public schema，须在 ignoreTable 中豁免
CREATE TABLE IF NOT EXISTS public.audit_log (
    id          BIGSERIAL PRIMARY KEY,
    -- 归属租户（审计回答"对哪个租户"）；登录时取 SecurityUser 首个租户，非空
    tenant_id   VARCHAR(32)  NOT NULL,
    -- 操作者
    user_id     BIGINT       NOT NULL,
    -- LOGIN / DELETE_DOCUMENT / EXECUTE_TOOL / DATABASE_QUERY / DOWNLOAD / MCP_TOOL 等
    action_type VARCHAR(32)  NOT NULL,
    target_type VARCHAR(32),
    target_id   VARCHAR(64),
    detail      TEXT,
    ip_address  VARCHAR(45),
    created_at  TIMESTAMP    NOT NULL DEFAULT now()
);
-- 查询索引（匹配 admin 按租户+操作类型+时间过滤）
CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_action_time ON public.audit_log (tenant_id, action_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_created ON public.audit_log (created_at DESC);