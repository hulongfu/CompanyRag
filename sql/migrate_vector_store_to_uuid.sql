-- ================================================
-- 修复 vector_store 表 ID 类型
-- 将 id 从 BIGSERIAL 改为 UUID 类型
-- ================================================

-- 注意：执行此脚本前请备份数据！
-- 如果 vector_store 表已有数据，需要先导出再迁移

-- 1. 删除旧的 vector_store 表（如果存在数据请先备份）
DROP TABLE IF EXISTS vector_store CASCADE;

-- 2. 重新创建 vector_store 表，id 列使用 UUID 类型
CREATE TABLE vector_store (
    id UUID PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(1024)
);

-- 3. 创建 HNSW 索引
CREATE INDEX idx_vector_store_embedding ON vector_store
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- ================================================
-- 对于租户 Schema，需要类似地修改每个租户的 vector_store 表
-- 示例：修改 tenant_xxx schema 中的 vector_store 表
-- ================================================
-- 对每个租户 schema 执行：
-- DROP TABLE IF EXISTS tenant_xxx.vector_store CASCADE;
-- CREATE TABLE tenant_xxx.vector_store (
--     id UUID PRIMARY KEY,
--     content TEXT,
--     metadata JSONB,
--     embedding vector(1024)
-- );
-- CREATE INDEX idx_tenant_xxx_vector_store_embedding ON tenant_xxx.vector_store
--     USING hnsw (embedding vector_cosine_ops)
--     WITH (m = 16, ef_construction = 64);
