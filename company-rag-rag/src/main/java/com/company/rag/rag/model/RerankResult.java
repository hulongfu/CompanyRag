package com.company.rag.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rerank 结果项
 */
public record RerankResult(
    int index,
    String document,
    @JsonProperty("relevance_score") double relevanceScore
) {}
