-- =====================================================
-- 混合检索优化 Schema 迁移脚本
-- 日期：2026-07-26
-- 说明：为 vector_store 表添加全文检索和模糊匹配支持
-- =====================================================

-- 1. 添加 tsvector 列用于全文检索
ALTER TABLE vector_store 
ADD COLUMN content_tsv tsvector;

-- 2. 创建 GIN 索引加速全文检索
CREATE INDEX idx_vector_store_content_tsv 
ON vector_store USING GIN (content_tsv);

-- 3. 创建触发器自动更新 tsvector
CREATE TRIGGER tsvectorupdate 
BEFORE INSERT OR UPDATE ON vector_store
FOR EACH ROW EXECUTE FUNCTION
tsvector_update_trigger(
    content_tsv, 'pg_catalog.simple', content
);

-- 4. 初始化现有数据的 tsvector
UPDATE vector_store 
SET content_tsv = to_tsvector('pg_catalog.simple', content);

-- 5. 启用 pg_trgm 扩展用于模糊匹配（只需执行一次）
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 6. 创建 trgm 索引加速模糊匹配
CREATE INDEX idx_vector_store_content_trgm 
ON vector_store USING GIN (content gin_trgm_ops);

-- =====================================================
-- 回滚脚本（如需撤销）
-- =====================================================
-- DROP INDEX IF EXISTS idx_vector_store_content_trgm;
-- DROP INDEX IF EXISTS idx_vector_store_content_tsv;
-- DROP TRIGGER IF EXISTS tsvectorupdate ON vector_store;
-- ALTER TABLE vector_store DROP COLUMN IF EXISTS content_tsv;
-- DROP EXTENSION IF EXISTS pg_trgm;
