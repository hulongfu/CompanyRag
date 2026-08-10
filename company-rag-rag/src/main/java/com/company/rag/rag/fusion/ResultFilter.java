package com.company.rag.rag.fusion;

import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.RagResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 结果筛选器
 */
@Slf4j
@Component
public class ResultFilter {
    
    private static final double DEFAULT_SCORE_THRESHOLD = 0.3;
    
    /**
     * 阈值过滤 + Top-K（不再做多样性去重）
     * @param results 融合后的结果
     * @param topK 最终返回数量
     * @param scoreThreshold 分数阈值
     * @return 筛选后的结果
     */
    public List<FusedResult> filter(List<FusedResult> results, int topK, Double scoreThreshold) {
        double threshold = scoreThreshold != null ? scoreThreshold : DEFAULT_SCORE_THRESHOLD;
        
        log.info("开始筛选 | 原始数量={} | 阈值={} | topK={}", results.size(), threshold, topK);
        
        return results.stream()
            // 1. 阈值过滤
            .filter(r -> {
                boolean pass = r.getFinalScore() >= threshold;
                if (!pass) {
                    log.debug("阈值过滤 | chunkId={} | score={}", r.getChunkId(), r.getFinalScore());
                }
                return pass;
            })
            // 2. 按分数排序取 Top-K
            .sorted(Comparator.comparingDouble(FusedResult::getFinalScore).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }
    
    /**
     * Rerank 后的最终筛选：按文档分组，每组最多保留 maxPerDoc 条
     * 
     * @param results Rerank 后的结果
     * @param topK 最终返回数量
     * @param maxPerDoc 每文档最多保留条数
     * @return 筛选后的结果
     */
    public List<RagResult.ChunkResult> finalFilter(List<RagResult.ChunkResult> results, 
                                                    int topK, int maxPerDoc) {
        log.info("开始最终筛选 | 原始数量={} | topK={} | maxPerDoc={}", 
                 results.size(), topK, maxPerDoc);
        
        // 1. 按文档名分组
        Map<String, List<RagResult.ChunkResult>> grouped = results.stream()
            .collect(Collectors.groupingBy(RagResult.ChunkResult::getDocumentName));
        
        log.debug("分组统计 | 文档数={}", grouped.size());
        
        // 2. 每组保留分数最高的 maxPerDoc 条（rerankScore 可能为 null，使用 nullsLast 避免 NPE）
        List<RagResult.ChunkResult> filtered = grouped.values().stream()
            .flatMap(list -> {
                // 组内按 rerankScore 降序（null 值排最后）
                List<RagResult.ChunkResult> sorted = list.stream()
                    .sorted(Comparator.comparing(
                        RagResult.ChunkResult::getRerankScore,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(maxPerDoc)
                    .collect(Collectors.toList());
                
                if (!sorted.isEmpty()) {
                    log.debug("文档去重 | document={} | 保留{}条", 
                              sorted.get(0).getDocumentName(), sorted.size());
                }
                return sorted.stream();
            })
            .collect(Collectors.toList());
        
        // 3. 全局按 rerankScore 排序取 Top-K（rerankScore 可能为 null，使用 nullsLast 避免 NPE）
        return filtered.stream()
            .sorted(Comparator.comparing(
                RagResult.ChunkResult::getRerankScore,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(topK)
            .collect(Collectors.toList());
    }
}
