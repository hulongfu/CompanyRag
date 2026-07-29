package com.company.rag.rag.rerank;

import com.company.rag.rag.model.RerankResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 诊断测试：验证 SiliconFlow Rerank API 响应的反序列化
 */
class RerankResponseDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeSiliconFlowResponseWithDocumentAsObject() throws Exception {
        // SiliconFlow Rerank API return_documents=true 时的响应格式
        String json = """
            {
              "id": "cmpl-abc123",
              "results": [
                {
                  "index": 0,
                  "relevance_score": 0.95,
                  "document": {
                    "text": "测试文档内容"
                  }
                }
              ],
              "meta": {
                "tokens": {
                  "input_tokens": 10,
                  "output_tokens": 0
                },
                "billed_units": {
                  "input_tokens": 10,
                  "output_tokens": 0,
                  "search_units": 1
                }
              }
            }
            """;

        RerankResponse response = objectMapper.readValue(json, RerankResponse.class);
        assertThat(response.id()).isEqualTo("cmpl-abc123");
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).index()).isEqualTo(0);
        assertThat(response.results().get(0).relevanceScore()).isEqualTo(0.95);
    }

    @Test
    void shouldDeserializeMinimalResponse() throws Exception {
        // return_documents=false 时的响应（无 document 字段）
        String json = """
            {
              "id": "cmpl-abc123",
              "results": [
                {
                  "index": 0,
                  "relevance_score": 0.95
                }
              ]
            }
            """;

        RerankResponse response = objectMapper.readValue(json, RerankResponse.class);
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).relevanceScore()).isEqualTo(0.95);
    }

    @Test
    void shouldDeserializeResponseWithDocumentAsString() throws Exception {
        // document 为字符串的情况（某些 API 版本）
        String json = """
            {
              "id": "cmpl-abc123",
              "results": [
                {
                  "index": 0,
                  "relevance_score": 0.95,
                  "document": "测试文档内容"
                }
              ]
            }
            """;

        RerankResponse response = objectMapper.readValue(json, RerankResponse.class);
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).document()).isEqualTo("测试文档内容");
    }

    @Test
    void shouldIgnoreUnknownFields() throws Exception {
        // API 返回未知字段时不应报错
        String json = """
            {
              "id": "cmpl-abc123",
              "results": [
                {
                  "index": 0,
                  "relevance_score": 0.95,
                  "some_unknown_field": "value"
                }
              ],
              "meta": {
                "tokens": {
                  "input_tokens": 10,
                  "unknown_token_field": 5
                },
                "billed_units": {
                  "input_tokens": 10,
                  "search_units": 1
                }
              },
              "some_top_level_unknown": "value"
            }
            """;

        RerankResponse response = objectMapper.readValue(json, RerankResponse.class);
        assertThat(response.results()).hasSize(1);
    }
}
