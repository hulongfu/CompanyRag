-- ================================================
-- 会话历史功能数据库迁移脚本
-- ================================================
-- ⚠️ 已废弃：会话表现在通过 TenantServiceImpl.createTenantSchema() 动态创建
-- 此脚本仅用于参考，不要单独执行
-- 
-- 原因：
-- 1. 无 schema 限定，单独执行会落到 public schema
-- 2. 实际部署中，每个租户的 schema 中都会创建这些表
-- 3. 动态创建确保新租户自动获得完整的表结构
-- ================================================

-- rag_session_meta 表（会话元信息）
CREATE TABLE IF NOT EXISTS rag_session_meta (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    title VARCHAR(256),
    last_query TEXT,
    message_count INTEGER DEFAULT 0,
    is_deleted BOOLEAN DEFAULT FALSE,
    tags JSONB DEFAULT '[]'::jsonb,
    metadata JSONB DEFAULT '{}'::jsonb,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_session_meta_tenant_user ON rag_session_meta(tenant_id, user_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_session_meta_deleted ON rag_session_meta(is_deleted) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_session_meta_tags ON rag_session_meta USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_session_meta_title_trgm ON rag_session_meta USING GIN(title gin_trgm_ops);

-- 行级安全策略（移除 postgres 后门，增加 WITH CHECK + FORCE RLS）
ALTER TABLE rag_session_meta ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_session_meta FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_session_meta ON rag_session_meta;
CREATE POLICY tenant_isolation_session_meta ON rag_session_meta
    FOR ALL
    TO company_rag_app
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- rag_session 表（如果不存在则创建）
CREATE TABLE IF NOT EXISTS rag_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT,
    query TEXT NOT NULL,
    answer TEXT,
    context TEXT,
    tokens_input INTEGER DEFAULT 0,
    tokens_output INTEGER DEFAULT 0,
    latency_ms INTEGER DEFAULT 0,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_session_session_id ON rag_session(session_id);
CREATE INDEX IF NOT EXISTS idx_session_tenant_create ON rag_session(tenant_id, create_time DESC);

-- 行级安全策略（移除 postgres 后门，增加 WITH CHECK + FORCE RLS）
ALTER TABLE rag_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_session FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_session ON rag_session;
CREATE POLICY tenant_isolation_session ON rag_session
    FOR ALL
    TO company_rag_app
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
