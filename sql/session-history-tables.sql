-- ================================================
-- 会话历史功能数据库迁移脚本
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

-- 行级安全策略
ALTER TABLE rag_session_meta ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_session_meta ON rag_session_meta;
CREATE POLICY tenant_isolation_session_meta ON rag_session_meta
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');

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

-- 行级安全策略（如果不存在）
ALTER TABLE rag_session ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_session ON rag_session;
CREATE POLICY tenant_isolation_session ON rag_session
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
