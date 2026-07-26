package com.company.rag.rag.fusion;

import com.company.rag.rag.model.FusedResult;
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
     * 多级筛选
     * @param results 融合后的结果
     * @param topK 最终返回数量
     * @param scoreThreshold 分数阈值
     * @return 筛选后的结果
     */
    public List<FusedResult> filter(List<FusedResult> results, int topK, Double scoreThreshold) {
        double threshold = scoreThreshold != null ? scoreThreshold : DEFAULT_SCORE_THRESHOLD;
        
        log.info("开始筛选 | 原始数量={} | 阈值={:.2f} | topK={}", results.size(), threshold, topK);
        
        return results.stream()
            // 1. 阈值过滤
            .filter(r -> {
                boolean pass = r.getFinalScore() >= threshold;
                if (!pass) {
                    log.debug("阈值过滤 | chunkId={} | score={:.4f}", r.getChunkId(), r.getFinalScore());
                }
                return pass;
            })
            // 2. 多样性去重（同文档只保留最佳）
            .collect(Collectors.groupingBy(FusedResult::getDocumentName))
            .values().stream()
            .map(list -> {
                FusedResult best = list.stream()
                    .max(Comparator.comparingDouble(FusedResult::getFinalScore))
                    .orElseThrow();
                log.debug("多样性去重 | document={} | 保留 chunkId={} | score={:.4f}",
                    best.getDocumentName(), best.getChunkId(), best.getFinalScore());
                return best;
            })
            // 3. 取 Top-K
            .sorted(Comparator.comparingDouble(FusedResult::getFinalScore).reversed())
            .limit(topK)
            .collect(Collectors.toList());
    }
}
