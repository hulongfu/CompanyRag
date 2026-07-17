-- ================================================
-- CompanyRag 数据库初始化脚本
-- ================================================
-- 设计原则：
-- 1. public schema 只存放系统级表（租户、用户）
-- 2. 每个租户的业务表（文档、chunk、向量、会话）独立存放在 tenant_{code} schema
-- 3. 通过 Schema 实现物理数据隔离
-- ================================================

-- 创建扩展
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ========== 系统表（public Schema） ==========

-- 租户表
CREATE TABLE IF NOT EXISTS public.sys_tenant (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL UNIQUE,
    tenant_name VARCHAR(128) NOT NULL,
    schema_name VARCHAR(64),             -- 独立Schema名称
    status INTEGER DEFAULT 1,            -- 0-禁用 1-启用
    contact_name VARCHAR(64),
    contact_phone VARCHAR(20),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 用户表
CREATE TABLE IF NOT EXISTS public.sys_user (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES public.sys_tenant(id),
    username VARCHAR(64) NOT NULL,
    password VARCHAR(256) NOT NULL,
    display_name VARCHAR(128),
    email VARCHAR(128),
    role VARCHAR(32) DEFAULT 'user',     -- admin / user / viewer
    status INTEGER DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, username)
);

-- ========== 业务表模板（用于动态创建租户Schema） ==========
-- 以下SQL通过 TenantServiceImpl.createTenantSchema() 动态执行
-- 每个租户独立 Schema 中会创建这些表

/* 业务表模板开始

-- 文档表
CREATE TABLE rag_document (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    file_name VARCHAR(256) NOT NULL,
    file_type VARCHAR(32),
    file_size BIGINT,
    file_path VARCHAR(512),
    title VARCHAR(256),
    chunk_count INTEGER DEFAULT 0,
    status INTEGER DEFAULT 0,            -- -1失败 0待处理 1已切分 2已向量化
    error_msg TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 文档切分块表
CREATE TABLE doc_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES rag_document(id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    token_count INTEGER DEFAULT 0,
    split_strategy VARCHAR(32),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 向量存储表（PGVector）
CREATE TABLE vector_store (
    id UUID PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(1024)
);
CREATE INDEX idx_vector_store_embedding ON vector_store
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 会话历史表
CREATE TABLE rag_session (
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
CREATE INDEX idx_doc_tenant ON rag_document(tenant_id);
CREATE INDEX idx_chunk_document ON doc_chunk(document_id);
CREATE INDEX idx_chunk_document_tenant ON doc_chunk(tenant_id, document_id);
CREATE INDEX idx_session_tenant ON rag_session(tenant_id, session_idapse);
CREATE INDEX idx_chunk_content_trgm ON doc_chunk USING gin (content gin_trgm_ops);
CREATE INDEX idx_document_title_trgm ON rag_document USING gin (title gin_trgm_ops);

-- 行级安全策略
ALTER TABLE rag_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE doc_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE rag_session ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_document ON rag_document
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
CREATE POLICY tenant_isolation_chunk ON doc_chunk
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
CREATE POLICY tenant_isolation_session ON rag_session
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');

业务表模板结束 */

-- ========== 默认数据 ==========
INSERT INTO public.sys_tenant (tenant_code, tenant_name, status)
VALUES ('default', '默认租户', 1)
ON CONFLICT (tenant_code) DO NOTHING;

UPDATE public.sys_tenant SET schema_name = 'tenant_default' WHERE tenant_code = 'default';

-- 默认密码: admin123 (BCrypt加密)
INSERT INTO public.sys_user (tenant_id, username, password, display_name, role)
VALUES (1, 'admin', '$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6', '管理员', 'admin')
ON CONFLICT DO NOTHING;

-- ========== RLS辅助函数（用于所有Schema） ==========
CREATE OR REPLACE FUNCTION set_tenant_id(p_tenant_id BIGINT) RETURNS VOID AS $$
BEGIN
    EXECUTE format('SET LOCAL app.tenant_id = %L', p_tenant_id);
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS BIGINT AS $$
BEGIN
    RETURN COALESCE(current_setting('app.tenant_id', true)::BIGINT, 0);
END;
$$ LANGUAGE plpgsql STABLE;