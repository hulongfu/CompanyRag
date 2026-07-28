package com.company.rag.rag.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rerank 元数据
 * 注：所有字段使用包装类型以容忍 API 响应缺失
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RerankMeta(
    RerankTokens tokens,
    @JsonProperty("billed_units") RerankBilledUnits billedUnits
) {}

/**
 * Token 统计
 * 注：使用包装类型和默认值容忍 API 响应缺失
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record RerankTokens(
    @JsonProperty("input_tokens") Integer inputTokens,
    @JsonProperty("output_tokens") Integer outputTokens,
    @JsonProperty("image_tokens") Integer imageTokens
) {}

/**
 * 计费单位
 * 注：使用包装类型和默认值容忍 API 响应缺失
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record RerankBilledUnits(
    @JsonProperty("input_tokens") Integer inputTokens,
    @JsonProperty("output_tokens") Integer outputTokens,
    @JsonProperty("image_tokens") Integer imageTokens,
    @JsonProperty("search_units") Integer searchUnits,
    Integer classifications
) {}
