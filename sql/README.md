# 数据库说明

## 数据库初始化

```bash
# 方式一：Docker Compose 自动初始化
docker compose up -d postgres

# 方式二：手动导入
psql -h localhost -U postgres -d company_rag -f init.sql
```

## 表结构概览

| 表名 | 说明 | 是否公共表 |
|------|------|-----------|
| sys_tenant | 租户信息 | 是 |
| sys_user | 用户信息 | 是(tenant_id隔离) |
| rag_document | 文档元数据 | 是(tenant_id隔离) |
| doc_chunk | 文档切分块 | 是(tenant_id隔离) |
| vector_store | 向量存储(PGVector) | 是(metadata->>'tenant_id'过滤) |
| rag_session | 对话历史 | 是(tenant_id隔离) |

## PGVector 说明

- 向量维度: 1024 (通义千问 text-embedding-v3)
- 索引类型: HNSW (余弦距离)
- 距离算法: COSINE_DISTANCE
