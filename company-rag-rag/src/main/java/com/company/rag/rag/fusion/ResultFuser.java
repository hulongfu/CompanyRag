package com.company.rag.rag.fusion;

import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.NormalizedResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 结果融合器（动态权重调整）
 */
@Slf4j
@Component
public class ResultFuser {
    
    // 权重策略：[vector, fulltext, fuzzy]
    private static final double[] WEIGHTS_SHORT = {0.7, 0.2, 0.1};
    private static final double[] WEIGHTS_LONG = {0.4, 0.4, 0.2};
    private static final double[] WEIGHTS_PROPER = {0.5, 0.4, 0.1};
    
    /**
     * 融合三路检索结果
     */
    public List<FusedResult> fuse(
        List<NormalizedResult> vector,
        List<NormalizedResult> fulltext,
        List<NormalizedResult> fuzzy,
        String query
    ) {
        double[] weights = determineWeights(query);
        log.info("融合权重 | query={} | vector={}, fulltext={}, fuzzy={}",
                query, 
                String.format("%.2f", weights[0]),
                String.format("%.2f", weights[1]),
                String.format("%.2f", weights[2]));
        
        // 合并所有结果（按 chunkId 分组）
        Map<String, FusedResult> fusedMap = new LinkedHashMap<>();
        
        // 融合向量路结果
        for (NormalizedResult r : vector) {
            FusedResult fr = fusedMap.computeIfAbsent(r.getChunkId(), k -> new FusedResult(r));
            fr.addScore(weights[0], r.getNormalizedScore());
        }
        
        // 融合全文路结果
        for (NormalizedResult r : fulltext) {
            FusedResult fr = fusedMap.computeIfAbsent(r.getChunkId(), k -> new FusedResult(r));
            fr.addScore(weights[1], r.getNormalizedScore());
        }
        
        // 融合模糊路结果
        for (NormalizedResult r : fuzzy) {
            FusedResult fr = fusedMap.computeIfAbsent(r.getChunkId(), k -> new FusedResult(r));
            fr.addScore(weights[2], r.getNormalizedScore());
        }
        
        // 按最终分数排序
        return fusedMap.values().stream()
            .sorted(Comparator.comparingDouble(FusedResult::getFinalScore).reversed())
            .collect(Collectors.toList());
    }
    
    /**
     * 根据 Query 特征确定权重
     */
    private double[] determineWeights(String query) {
        String[] terms = query.split("\\s+");
        
        // 判断是否含专有名词
        boolean hasProperNoun = query.matches(".*[A-Z].*") || 
                                query.matches(".*\\d.*") ||
                                query.contains("-") ||
                                query.contains("_");
        
        if (hasProperNoun) {
            return WEIGHTS_PROPER;
        } else if (terms.length < 5) {
            return WEIGHTS_SHORT;
        } else {
            return WEIGHTS_LONG;
        }
    }
}
