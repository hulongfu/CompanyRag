-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(32)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    action_type VARCHAR(32)  NOT NULL,
    target_type VARCHAR(32),
    target_id   VARCHAR(64),
    detail      TEXT,
    ip_address  VARCHAR(45),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_action ON audit_log(tenant_id, action_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_created_at   ON audit_log(created_at DESC);
