package com.company.rag.rag.rerank;

import com.company.rag.common.IntegrationTest;
import com.company.rag.rag.model.RerankResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@IntegrationTest
class SiliconFlowRerankClientIntegrationTest {

    @Autowired
    private RerankModel rerankModel;

    @Test
    void testRerank_withRealApi() {
        // Given
        String query = "什么是微服务架构？";
        List<String> documents = List.of(
            "微服务是一种架构风格，将单一应用程序划分为一组小的服务",
            "单体架构是传统的软件架构风格，所有功能打包在一起",
            "容器技术促进了微服务的发展，Docker 和 Kubernetes 成为标配",
            "数据库设计需要考虑数据一致性和可用性"
        );
        int topN = 3;

        // When
        long startTime = System.currentTimeMillis();
        RerankResponse response = rerankModel.rerank(query, documents, topN);
        long latency = System.currentTimeMillis() - startTime;

        // Then
        assertThat(response.results()).hasSize(topN);
        assertThat(response.results().get(0).relevanceScore()).isBetween(0.0, 1.0);
        assertThat(latency).isLessThan(5000); // 首次调用可能有网络延迟，放宽限制

        System.out.println("集成测试通过 | 响应时间=" + latency + "ms");
    }

    @Test
    void testRerank_emptyDocuments() {
        // Given
        String query = "测试查询";
        List<String> documents = List.of();
        int topN = 3;

        // When
        RerankResponse response = rerankModel.rerank(query, documents, topN);

        // Then
        assertThat(response.results()).isEmpty();
    }

    @Test
    void testRerank_singleDocument() {
        // Given
        String query = "测试";
        List<String> documents = List.of("单个文档内容");
        int topN = 1;

        // When
        RerankResponse response = rerankModel.rerank(query, documents, topN);

        // Then
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).relevanceScore()).isBetween(0.0, 1.0);
    }
}
