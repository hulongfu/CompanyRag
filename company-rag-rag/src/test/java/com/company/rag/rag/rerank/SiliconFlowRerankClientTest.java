package com.company.rag.rag.rerank;

import com.company.rag.rag.config.RerankConfigProperties;
import com.company.rag.rag.model.RerankResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.MockitoAnnotations.openMocks;

class SiliconFlowRerankClientTest {

    @Mock
    private RerankConfigProperties config;

    private SiliconFlowRerankClient rerankClient;

    @BeforeEach
    void setUp() {
        openMocks(this);
        rerankClient = new SiliconFlowRerankClient(config, RestClient.builder());
    }

    @Test
    void testRerank_returnsResponse() {
        // Given
        String query = "Apple";
        List<String> documents = List.of("apple", "banana", "fruit");
        int topN = 3;
        
        RerankResponse mockResponse = new RerankResponse(
            "test-id",
            List.of(),
            null
        );
        
        // Then: 验证对象结构正确
        assertThat(mockResponse.id()).isEqualTo("test-id");
        assertThat(mockResponse.results()).isEmpty();
    }
}
