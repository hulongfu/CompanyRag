package com.company.rag.rag.retriever;

import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.service.MultiRetrieveService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多路混合检索集成测试（需要手动执行）
 * 
 * 测试向量 + 全文 + 模糊三路检索的完整链路
 * 
 * ⚠️ 运行条件：
 * 1. Redis 服务已启动（docker-redis-1 容器）
 * 2. PostgreSQL 服务已启动（company-rag-postgres 容器）
 * 3. 数据库 vector_store 表中有测试数据
 * 4. 已执行 sql/hybrid-search-schema-migration.sql 脚本
 * 
 * 运行方式：
 * 1. 移除 @Disabled 注解
 * 2. mvn test -Dtest=MultiRetrieveIntegrationTest -Dspring.profiles.active=dev
 * 
 * 如果只需要验证代码逻辑，请运行 MultiRetrieveComponentsTest（无需外部依赖，3 tests PASS）
 */
@Disabled("需要 Redis 和 PostgreSQL 环境，手动执行")
@SpringBootTest
@ActiveProfiles("dev")
class MultiRetrieveIntegrationTest {
    
    @Autowired
    private MultiRetrieveService multiRetrieveService;
    
    /**
     * 测试完整检索链路
     * 需要数据库中有测试数据才能验证效果
     */
    @Test
    void testHybridRetrieve_fullChain() {
        // Given: 创建一个查询
        RagQuery query = new RagQuery();
        query.setQuery("微服务架构");
        query.setTenantId(1L);
        query.setTopK(10);
        query.setRetrievalStrategy("HYBRID");
        query.setFusionTopK(10);
        query.setScoreThreshold(0.3);
        query.setEnableRerank(false);
        
        // When: 执行多路混合检索
        List<RagResult.ChunkResult> results = multiRetrieveService.retrieve(query);
        
        // Then: 验证结果
        assertNotNull(results, "检索结果不应为 null");
        System.out.println("检索到 " + results.size() + " 条结果");
        
        // 如果有结果，验证数据结构
        if (!results.isEmpty()) {
            results.forEach(result -> {
                assertNotNull(result.getDocumentId(), "documentId 不应为 null");
                assertNotNull(result.getContent(), "content 不应为 null");
                assertTrue(result.getFinalScore() >= 0, "finalScore 应该 >= 0");
            });
        }
    }
    
    /**
     * 测试带 Rerank 的检索链路
     */
    @Test
    void testHybridRetrieve_withRerank() {
        // Given
        RagQuery query = new RagQuery();
        query.setQuery("Spring Boot");
        query.setTenantId(1L);
        query.setTopK(20);
        query.setRetrievalStrategy("HYBRID");
        query.setFusionTopK(10);
        query.setScoreThreshold(0.3);
        query.setEnableRerank(true);  // 启用 Rerank
        
        // When
        List<RagResult.ChunkResult> results = multiRetrieveService.retrieve(query);
        
        // Then
        assertNotNull(results);
        System.out.println("Rerank 后检索到 " + results.size() + " 条结果");
        
        // Rerank 后的结果应该有 rerankScore
        if (!results.isEmpty()) {
            results.forEach(result -> {
                if (result.getRerankScore() != null) {
                    assertTrue(result.getRerankScore() >= 0, "rerankScore 应该 >= 0");
                }
            });
        }
    }
    
    /**
     * 测试空结果场景
     */
    @Test
    void testHybridRetrieve_emptyResults() {
        // Given: 查询一个不存在的词
        RagQuery query = new RagQuery();
        query.setQuery("xyz123abc456");
        query.setTenantId(1L);
        query.setTopK(5);
        query.setRetrievalStrategy("HYBRID");
        query.setFusionTopK(5);
        query.setScoreThreshold(0.3);
        
        // When
        List<RagResult.ChunkResult> results = multiRetrieveService.retrieve(query);
        
        // Then: 应该返回空列表或很少的结果
        assertNotNull(results);
        System.out.println("空结果测试：" + results.size() + " 条结果");
    }
}
