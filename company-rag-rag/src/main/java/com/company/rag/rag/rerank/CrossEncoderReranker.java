package com.company.rag.rag.rerank;

import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.model.RerankResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Cross-Encoder 重排序器
 * 使用专用 Rerank 模型进行精细化相关性评分
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossEncoderReranker {

    private final RerankModel rerankModel;

    /**
     * 对检索结果进行重排序
     *
     * @param query 原始查询
     * @param chunks 待重排序的文档块
     * @param topK 保留前 K 条
     * @return 重排序后的结果
     */
    @CircuitBreaker(name = "rerank", fallbackMethod = "rerankFallback")
    @RateLimiter(name = "rag-rate-limiter", fallbackMethod = "rerankFallback")
    public List<RagResult.ChunkResult> rerank(String query, List<RagResult.ChunkResult> chunks, int topK) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        long startTime = System.currentTimeMillis();
        log.info("开始重排序 | 候选数={} | topK={}", chunks.size(), topK);

        // 1. 提取文档内容
        List<String> documents = chunks.stream()
                .map(RagResult.ChunkResult::getContent)
                .collect(Collectors.toList());

        // 2. 调用 RerankModel
        RerankResponse response = rerankModel.rerank(query, documents, topK);

        // 3. 将结果映射回 ChunkResult
        List<RagResult.ChunkResult> reranked = mapToChunkResults(response, chunks);

        long latency = System.currentTimeMillis() - startTime;
        log.info("重排序完成 | 结果数={} | 耗时={}ms", reranked.size(), latency);

        return reranked;
    }

    /**
     * 将 RerankResponse 映射为 ChunkResult 列表
     */
    private List<RagResult.ChunkResult> mapToChunkResults(RerankResponse response, 
                                                           List<RagResult.ChunkResult> originalChunks) {
        // 创建索引到原始 chunk 的映射
        Map<Integer, RagResult.ChunkResult> indexToChunk = new HashMap<>();
        for (int i = 0; i < originalChunks.size(); i++) {
            indexToChunk.put(i, originalChunks.get(i));
        }

        // 根据 rerank 结果重新排序
        return response.results().stream()
                .map(result -> {
                    RagResult.ChunkResult chunk = indexToChunk.get(result.index());
                    if (chunk != null) {
                        chunk.setRerankScore(result.relevanceScore());
                        chunk.setFinalScore(result.relevanceScore());
                    }
                    return chunk;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 熔断降级：按 finalScore 排序返回
     */
    public List<RagResult.ChunkResult> rerankFallback(String query, List<RagResult.ChunkResult> chunks,
                                                       int topK, Throwable t) {
        log.warn("重排序服务降级 | query={} | 候选数={} | 原因：{}", 
                 query, chunks.size(), t.getMessage());
        
        // 按 finalScore 降序排列
        return chunks.stream()
                .sorted(Comparator.comparingDouble(RagResult.ChunkResult::getFinalScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }
}
