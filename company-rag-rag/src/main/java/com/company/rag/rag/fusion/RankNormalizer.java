package com.company.rag.rag.fusion;

import com.company.rag.rag.model.NormalizedResult;
import com.company.rag.rag.model.RagResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 排名归一化器
 * 算法：normalized_score = 1.0 / (rank + 1)
 */
@Slf4j
@Component
public class RankNormalizer {
    
    /**
     * 对检索结果进行排名归一化
     * @param results 按排名排序的检索结果
     * @return 归一化后的结果
     */
    public List<NormalizedResult> normalize(List<RagResult.ChunkResult> results) {
        List<NormalizedResult> normalized = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            NormalizedResult nr = new NormalizedResult(results.get(i));
            nr.setNormalizedScore(1.0 / (i + 1));  // rank 从 0 开始
            normalized.add(nr);
        }
        return normalized;
    }
}
