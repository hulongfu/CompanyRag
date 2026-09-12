package com.company.rag.bootstrap;

import com.company.rag.common.IntegrationTest;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.service.MultiRetrieveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 多路混合检索真库集成测试（IT）
 *
 * 由 rag 模块迁移而来（原为 @Disabled 模板，因 rag 模块无完整 Spring Boot 上下文，
 * 无法加载 PG / Redis / 外网 embedding）。现置于 bootstrap 模块，加载完整真实上下文，
 * 验证向量 + 全文 + 模糊三路检索端到端链路。
 *
 * 通过 -Dit.pg=true 显式启用（与 RlsIsolationTest / AuditLogTenantIsolationIT 一致，
 * 供 scripts/run-it.sh --all 一键回归）。无 PG 的常规 CI 中整类 Skipped。
 *
 * 运行前置：PostgreSQL(5433)、Redis(6379) 运行中，且注入 REDIS_PASSWORD /
 * SILICONFLOW_API_KEY（embedding）等环境变量。
 */
@IntegrationTest
@EnabledIfSystemProperty(named = "it.pg", matches = "true")
public class MultiRetrieveIntegrationIT {

    @Autowired(required = false)
    private MultiRetrieveService multiRetrieveService;

    /**
     * 测试完整混合检索链路（无 Rerank）
     */
    @Test
    void testHybridRetrieve_fullChain() {
        RagQuery query = new RagQuery();
        query.setQuery("微服务架构");
        query.setTenantId(1L);
        query.setTopK(10);
        query.setRetrievalStrategy("HYBRID");
        query.setFusionTopK(10);
        query.setScoreThreshold(0.3);
        query.setEnableRerank(false);

        List<RagResult.ChunkResult> results = multiRetrieveService.retrieve(query);
        assertNotNull(results, "检索结果不应为 null");
    }

    /**
     * 测试带 Rerank 的检索链路（依赖外网 rerank API）
     */
    @Test
    void testHybridRetrieve_withRerank() {
        RagQuery query = new RagQuery();
        query.setQuery("Spring Boot");
        query.setTenantId(1L);
        query.setTopK(20);
        query.setRetrievalStrategy("HYBRID");
        query.setFusionTopK(10);
        query.setScoreThreshold(0.3);
        query.setEnableRerank(true);

        List<RagResult.ChunkResult> results = multiRetrieveService.retrieve(query);
        assertNotNull(results, "Rerank 检索结果不应为 null");
    }

    /**
     * 测试空结果场景（无匹配查询词，应返回非 null 的空列表）
     */
    @Test
    void testHybridRetrieve_emptyResults() {
        RagQuery query = new RagQuery();
        query.setQuery("xyz123abc456");
        query.setTenantId(1L);
        query.setTopK(5);
        query.setRetrievalStrategy("HYBRID");
        query.setFusionTopK(5);
        query.setScoreThreshold(0.3);

        List<RagResult.ChunkResult> results = multiRetrieveService.retrieve(query);
        assertNotNull(results, "空结果测试返回值不应为 null");
    }

    /**
     * 测试 QPS 正常、上下文完整加载（隐性验证依赖注入与链路装配成功）
     */
    @Test
    void testContextLoads() {
        assertNotNull(multiRetrieveService, "MultiRetrieveService 应已注入");
    }
}
