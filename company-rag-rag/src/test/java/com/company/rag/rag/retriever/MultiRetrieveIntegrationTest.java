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
 * ⚠️ 此测试被 @Disabled，原因：
 * - company-rag-rag 模块没有数据库和 Redis 依赖
 * - 需要完整的 Spring Boot 上下文（bootstrap 模块）
 * - 核心逻辑已通过 MultiRetrieveComponentsTest 覆盖（3 tests PASS）
 * 
 * 如需手动执行完整集成测试，请：
 * 1. 确保 Redis 运行：docker ps | grep redis
 * 2. 确保 PostgreSQL 运行：docker ps | grep postgres
 * 3. 确保数据库中有测试数据
 * 4. 在 bootstrap 模块中创建类似的测试类，使用 @SpringBootTest(classes = CompanyRagApplication.class)
 * 
 * 自动化测试请运行：mvn test -Dtest=MultiRetrieveComponentsTest
 */
@Disabled("需要完整的 Spring Boot 环境，请在 bootstrap 模块中执行集成测试")
@SpringBootTest
@ActiveProfiles("dev")
class MultiRetrieveIntegrationTest {
    
    // 此测试类仅作为集成测试的参考模板
    // 实际执行需要在 bootstrap 模块中配置完整的测试环境
    
    @Autowired(required = false)
    private MultiRetrieveService multiRetrieveService;
    
    /**
     * 测试完整检索链路
     */
    @Test
    void testHybridRetrieve_fullChain() {
        // Given
        RagQuery query = new RagQuery();
        query.setQuery("微服务架构");
        query.setTenantId(1L);
        query.setTopK(10);
        query.setRetrievalStrategy("HYBRID");
        query.setFusionTopK(10);
        query.setScoreThreshold(0.3);
        query.setEnableRerank(false);
        
        // When
        List<RagResult.ChunkResult> results = multiRetrieveService.retrieve(query);
        
        // Then
        assertNotNull(results, "检索结果不应为 null");
        System.out.println("检索到 " + results.size() + " 条结果");
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
        query.setEnableRerank(true);
        
        // When
        List<RagResult.ChunkResult> results = multiRetrieveService.retrieve(query);
        
        // Then
        assertNotNull(results);
        System.out.println("Rerank 后检索到 " + results.size() + " 条结果");
    }
    
    /**
     * 测试空结果场景
     */
    @Test
    void testHybridRetrieve_emptyResults() {
        // Given
        RagQuery query = new RagQuery();
        query.setQuery("xyz123abc456");
        query.setTenantId(1L);
        query.setTopK(5);
        query.setRetrievalStrategy("HYBRID");
        query.setFusionTopK(5);
        query.setScoreThreshold(0.3);
        
        // When
        List<RagResult.ChunkResult> results = multiRetrieveService.retrieve(query);
        
        // Then
        assertNotNull(results);
        System.out.println("空结果测试：" + results.size() + " 条结果");
    }
}
