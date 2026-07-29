package com.company.rag.rag.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Rerank 响应对象
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RerankResponse(
    String id,
    java.util.List<RerankResult> results,
    RerankMeta meta
) {}
