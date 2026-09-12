-- ================================================
-- CompanyRag IT 种子数据（幂等）
-- 说明：
--   仅用于本地一键 IT 回归（scripts/run-it.sh --seed）。
--   通过 docker exec 在 docker-pgvector-1 容器内以 company_rag_app 执行。
--   rag_document / doc_chunk / rag_session 已启用 FORCE RLS，
--   故在 INSERT 前必须 SET LOCAL app.tenant_id，否则被 WITH CHECK 拦截。
--   使用独立前缀 it_seed_ 避免与 RlsIsolationTest 的 rls_test_ 残留冲突。
-- ================================================

-- 租户 1 文档种子
SET LOCAL app.tenant_id = 1;
INSERT INTO tenant_default.rag_document (tenant_id, file_name, file_type, title, status, chunk_count)
SELECT 1, 'it_seed_microservice.txt', 'TXT', '微服务架构实战指南', 1, 2
WHERE NOT EXISTS (SELECT 1 FROM tenant_default.rag_document WHERE file_name = 'it_seed_microservice.txt');

INSERT INTO tenant_default.doc_chunk (document_id, tenant_id, chunk_index, content, split_strategy, token_count)
SELECT d.id, 1, 0, '微服务架构将单一应用拆分为多个独立部署的小服务，每个服务围绕业务能力组织，通过轻量级通信机制协同。', 'fixed', 40
FROM tenant_default.rag_document d WHERE d.file_name='it_seed_microservice.txt'
  AND NOT EXISTS (SELECT 1 FROM tenant_default.doc_chunk c WHERE c.document_id=d.id AND c.chunk_index=0);

INSERT INTO tenant_default.doc_chunk (document_id, tenant_id, chunk_index, content, split_strategy, token_count)
SELECT d.id, 1, 1, 'Spring Cloud 提供服务发现、配置中心、网关与熔断等组件，是构建微服务基础设施的常用技术栈。', 'fixed', 38
FROM tenant_default.rag_document d WHERE d.file_name='it_seed_microservice.txt'
  AND NOT EXISTS (SELECT 1 FROM tenant_default.doc_chunk c WHERE c.document_id=d.id AND c.chunk_index=1);

-- 租户 1 会话种子
INSERT INTO tenant_default.rag_session (session_id, tenant_id, user_id, query, answer, tokens_input, tokens_output)
SELECT 'it_seed_sess_1', 1, 1, '什么是微服务架构？', '微服务架构将单体应用拆分为多个独立部署的服务。', 12, 20
WHERE NOT EXISTS (SELECT 1 FROM tenant_default.rag_session WHERE session_id='it_seed_sess_1');

-- 租户 2 文档种子（验证租户间不可串读）
SET LOCAL app.tenant_id = 2;
INSERT INTO tenant_default.rag_document (tenant_id, file_name, file_type, title, status, chunk_count)
SELECT 2, 'it_seed_finance.txt', 'TXT', '企业金融合规白皮书', 1, 1
WHERE NOT EXISTS (SELECT 1 FROM tenant_default.rag_document WHERE file_name = 'it_seed_finance.txt');
