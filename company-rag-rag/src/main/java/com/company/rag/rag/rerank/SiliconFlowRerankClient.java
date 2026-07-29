package com.company.rag.rag.rerank;

import com.company.rag.rag.config.RerankConfigProperties;
import com.company.rag.rag.model.RerankResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 硅基流动 Rerank 客户端实现
 */
@Slf4j
@Component
public class SiliconFlowRerankClient implements RerankModel {

    private final RerankConfigProperties config;
    private final RestClient restClient;

    public SiliconFlowRerankClient(RerankConfigProperties config, RestClient.Builder restClientBuilder) {
        this.config = config;
        this.restClient = restClientBuilder
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public RerankResponse rerank(String query, List<String> documents, int topN) {
        log.debug("调用硅基流动 Rerank API | query={} | documents={} | topN={}", 
                  query, documents.size(), topN);

        Map<String, Object> requestBody = Map.of(
            "model", config.getOptions().getModel(),
            "query", query,
            "documents", documents,
            "return_documents", false,
            "top_n", topN
        );

        RerankResponse response = restClient.post()
                .uri("/v1/rerank")
                .body(requestBody)
                .retrieve()
                .body(RerankResponse.class);

        log.debug("Rerank API 响应 | results={}", response.results().size());
        return response;
    }
}
