package com.company.rag.rag.model;

/**
 * Rerank 响应对象
 */
public record RerankResponse(
    String id,
    java.util.List<RerankResult> results,
    RerankMeta meta
) {}
