package com.company.rag.rag.rerank;

import com.company.rag.rag.config.RerankConfigProperties;
import com.company.rag.rag.model.RerankResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
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

    public SiliconFlowRerankClient(RerankConfigProperties config) {
        this.config = config;
        
        // 配置 HTTP 超时（使用 Duration API）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));   // 连接超时 5 秒
        factory.setReadTimeout(Duration.ofSeconds(10));     // 读取超时 10 秒
        
        // 自建 RestClient 实例，不依赖共享的 Builder，避免配置污染
        this.restClient = RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(factory)
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
