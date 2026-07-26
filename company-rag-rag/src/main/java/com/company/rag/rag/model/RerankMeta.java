package com.company.rag.rag.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rerank 元数据
 */
public record RerankMeta(
    RerankTokens tokens,
    @JsonProperty("billed_units") RerankBilledUnits billedUnits
) {}

/**
 * Token 统计
 */
record RerankTokens(
    @JsonProperty("input_tokens") int inputTokens,
    @JsonProperty("output_tokens") int outputTokens,
    @JsonProperty("image_tokens") int imageTokens
) {}

/**
 * 计费单位
 */
record RerankBilledUnits(
    @JsonProperty("input_tokens") int inputTokens,
    @JsonProperty("output_tokens") int outputTokens,
    @JsonProperty("image_tokens") int imageTokens,
    @JsonProperty("search_units") int searchUnits,
    int classifications
) {}
