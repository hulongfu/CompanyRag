package com.company.rag.rag.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rerank 结果项
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RerankResult(
    int index,
    Object document,
    @JsonProperty("relevance_score") double relevanceScore
) {}
