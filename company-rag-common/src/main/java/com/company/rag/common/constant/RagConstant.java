package com.company.rag.common.constant;

/**
 * RAG系统全局常量
 */
public interface RagConstant {

    /** 请求头-租户ID */
    String HEADER_TENANT_ID = "X-Tenant-Id";
    /** 请求头-用户ID */
    String HEADER_USER_ID = "X-User-Id";

    /** 向量维度（通义千问 text-embedding-v3 默认） */
    int VECTOR_DIMENSION = 1024;

    /** 默认切分块大小 */
    int DEFAULT_CHUNK_SIZE = 512;
    /** 默认切分重叠 */
    int DEFAULT_CHUNK_OVERLAP = 64;

    /** 检索默认返回条数 */
    int DEFAULT_TOP_K = 10;
    /** Rerank后保留条数 */
    int RERANK_TOP_K = 5;

    /** 缓存命名空间 */
    String CACHE_NAMESPACE = "company:rag:";
    /** 文档向量缓存前缀 */
    String CACHE_DOC_VECTOR = CACHE_NAMESPACE + "vector:";
    /** 会话缓存前缀 */
    String CACHE_SESSION = CACHE_NAMESPACE + "session:";
    /** 限流缓存前缀 */
    String CACHE_RATE_LIMIT = CACHE_NAMESPACE + "ratelimit:";
}
