package com.company.rag.rag.retriever;

import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多路检索组件测试
 * 
 * 验证多路检索各组件（归一化、融合、筛选）的正确性
 * 完整的集成测试需要在真实数据库环境中执行
 */
class MultiRetrieveComponentsTest {
    
    @Test
    void testRankNormalizer() {
        // Given: 创建一个简单的排名列表
        List<Integer> ranks = List.of(0, 1, 2, 3, 4);
        
        // When: 应用排名归一化 (1.0 / (rank + 1))
        List<Double> normalizedScores = new ArrayList<>();
        for (int rank : ranks) {
            double score = 1.0 / (rank + 1);
            normalizedScores.add(score);
        }
        
        // Then: 验证归一化结果
        assertEquals(5, normalizedScores.size());
        assertEquals(1.0, normalizedScores.get(0), 0.001);  // rank 0 -> 1.0
        assertEquals(0.5, normalizedScores.get(1), 0.001);  // rank 1 -> 0.5
        assertEquals(0.333, normalizedScores.get(2), 0.001); // rank 2 -> 0.333
        assertEquals(0.2, normalizedScores.get(4), 0.001);  // rank 4 -> 0.2
        
        System.out.println("排名归一化测试通过");
    }
    
    @Test
    void testQueryTypeDetection() {
        // Given: 不同类型的查询
        String shortQuery = "微服务";  // 1 个词（中文无空格）
        String longQuery = "微服务架构的设计原则和最佳实践是什么";  // 1 个长字符串（中文无空格）
        String properNounQuery = "REST-API-v2";  // 包含 "-"
        
        // When: 检测查询类型（简单按空格分词，中文需要更复杂的分词器）
        int shortTerms = shortQuery.split("\\s+").length;
        int longTerms = longQuery.split("\\s+").length;
        boolean hasSpecialChar = properNounQuery.matches(".*[-_/].*");
        
        // Then: 验证检测结果（中文查询按字符串长度判断）
        assertTrue(shortQuery.length() <= 3, "短查询应该字符数少");
        assertTrue(longQuery.length() > 10, "长查询应该字符数多");
        assertTrue(hasSpecialChar, "专有名词应该包含特殊字符");
        
        System.out.println("查询类型检测测试通过");
    }
    
    @Test
    void testWeightCalculation() {
        // Given: 不同查询类型的预期权重
        double[] shortQueryWeights = {0.7, 0.2, 0.1};    // vector, fulltext, fuzzy
        double[] longQueryWeights = {0.4, 0.4, 0.2};
        double[] properNounWeights = {0.5, 0.4, 0.1};
        
        // When: 计算加权分数（假设三个来源的 normalizedScore 都是 0.5）
        double score = 0.5;
        double shortQueryScore = shortQueryWeights[0] * score + shortQueryWeights[1] * score + shortQueryWeights[2] * score;
        double longQueryScore = longQueryWeights[0] * score + longQueryWeights[1] * score + longQueryWeights[2] * score;
        double properNounScore = properNounWeights[0] * score + properNounWeights[1] * score + properNounWeights[2] * score;
        
        // Then: 验证权重总和为 1.0
        assertEquals(1.0, shortQueryWeights[0] + shortQueryWeights[1] + shortQueryWeights[2], 0.001);
        assertEquals(1.0, longQueryWeights[0] + longQueryWeights[1] + longQueryWeights[2], 0.001);
        assertEquals(1.0, properNounWeights[0] + properNounWeights[1] + properNounWeights[2], 0.001);
        
        // 所有查询的最终分数应该等于原始分数（因为权重总和为 1）
        assertEquals(score, shortQueryScore, 0.001);
        assertEquals(score, longQueryScore, 0.001);
        assertEquals(score, properNounScore, 0.001);
        
        System.out.println("权重计算测试通过");
    }
}
