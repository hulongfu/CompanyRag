-- 创建pgvector扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 创建全文检索扩展
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ========== 系统表（公共Schema） ==========

-- 租户表
CREATE TABLE IF NOT EXISTS public.sys_tenant (
    id BIGSERIAL PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL UNIQUE,
    tenant_name VARCHAR(128) NOT NULL,
    schema_name VARCHAR(64),
    status INTEGER DEFAULT 1,
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
    role VARCHAR(32) DEFAULT 'user',
    status INTEGER DEFAULT 1,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(tenant_id, username)
);

-- 文档表
CREATE TABLE IF NOT EXISTS public.rag_document (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    file_name VARCHAR(256) NOT NULL,
    file_type VARCHAR(32),
    file_size BIGINT,
    file_path VARCHAR(512),
    title VARCHAR(256),
    chunk_count INTEGER DEFAULT 0,
    status INTEGER DEFAULT 0,
    error_msg TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 文档切分块表
CREATE TABLE IF NOT EXISTS public.doc_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES public.rag_document(id) ON DELETE CASCADE,
    tenant_id BIGINT NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    token_count INTEGER DEFAULT 0,
    split_strategy VARCHAR(32),
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 向量存储表（PGVector）
CREATE TABLE IF NOT EXISTS public.vector_store (
    id BIGSERIAL PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(1024)
);
-- HNSW索引 - 高性能近似最近邻搜索（优化参数）
CREATE INDEX IF NOT EXISTS idx_vector_store_embedding ON public.vector_store 
    USING hnsw (embedding vector_cosine_ops) 
    WITH (m = 16, ef_construction = 64);

-- 会话历史表
CREATE TABLE IF NOT EXISTS public.rag_session (
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
CREATE INDEX IF NOT EXISTS idx_doc_tenant ON public.rag_document(tenant_id);
CREATE INDEX IF NOT EXISTS idx_chunk_document ON public.doc_chunk(document_id);
CREATE INDEX IF NOT EXISTS idx_session_tenant ON public.rag_session(tenant_id, session_id);

-- 全文检索索引（用于关键词搜索）
CREATE INDEX IF NOT EXISTS idx_chunk_content_trgm ON public.doc_chunk USING gin (content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_document_title_trgm ON public.rag_document USING gin (title gin_trgm_ops);

-- 插入默认租户
INSERT INTO public.sys_tenant (tenant_code, tenant_name, status) VALUES ('default', '默认租户', 1) ON CONFLICT (tenant_code) DO NOTHING;
-- 默认密码: admin123 (BCrypt加密)
INSERT INTO public.sys_user (tenant_id, username, password, display_name, role) VALUES 
(1, 'admin', '$2a$10$N.ZOn9G6/YLFixAOPMg/h.z7pCu6v2XyFDtC4q.jeeGm/TEZyj3C6', '管理员', 'admin') ON CONFLICT DO NOTHING;

-- ================================================
-- 行级安全 (RLS) 策略
-- ================================================

-- 设置当前租户ID的函数
CREATE OR REPLACE FUNCTION set_tenant_id(p_tenant_id BIGINT) RETURNS VOID AS $$
BEGIN
    EXECUTE format('SET LOCAL app.tenant_id = %L', p_tenant_id);
END;
$$ LANGUAGE plpgsql;

-- 获取当前租户ID的函数
CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS BIGINT AS $$
BEGIN
    RETURN COALESCE(current_setting('app.tenant_id', true)::BIGINT, 0);
END;
$$ LANGUAGE plpgsql STABLE;

-- 在需要租户隔离的表上启用 RLS
ALTER TABLE public.sys_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.rag_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.doc_chunk ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.rag_session ENABLE ROW LEVEL SECURITY;

-- 创建 RLS 策略（admin角色或数据库超级用户可绕过RLS）
CREATE POLICY tenant_isolation_user ON public.sys_user
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');

CREATE POLICY tenant_isolation_document ON public.rag_document
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');

CREATE POLICY tenant_isolation_chunk ON public.doc_chunk
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');

CREATE POLICY tenant_isolation_session ON public.rag_session
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
