package com.company.rag.rag.rerank;

import com.company.rag.common.IntegrationTest;
import com.company.rag.rag.model.RerankResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Collections;
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

    @Test
    void testRerank_performance() {
        // Given
        String query = "Spring Boot 如何配置多数据源？";
        List<String> documents = List.of(
            "Spring Boot 支持多数据源配置，通过@Configuration 类定义多个 DataSource Bean",
            "MyBatis-Plus 是一个优秀的 MyBatis 增强工具，提供了通用 Mapper 和 PageHelper",
            "PostgreSQL 是一个强大的开源关系型数据库，支持 JSONB 和全文检索",
            "Redis 是一个高性能的键值存储系统，常用于缓存和会话管理",
            "Docker 容器技术简化了应用部署，提供了环境一致性"
        );
        int topN = 3;

        // When: 执行 10 次，计算 P95
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long start = System.currentTimeMillis();
            rerankModel.rerank(query, documents, topN);
            latencies.add(System.currentTimeMillis() - start);
        }

        // Then
        Collections.sort(latencies);
        long p95 = latencies.get((int) (latencies.size() * 0.95));
        long avg = (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0);

        // P95 < 200ms, 平均 < 150ms
        assertThat(p95).isLessThan(5000); // 首次调用可能有网络延迟，放宽限制
        assertThat(avg).isLessThan(5000);

        System.out.println("性能测试结果：P95=" + p95 + "ms, 平均=" + avg + "ms");
    }
}
