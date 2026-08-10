package com.company.rag.rag.service.impl;

import com.company.rag.rag.fusion.RankNormalizer;
import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.fusion.ResultFuser;
import com.company.rag.rag.model.*;
import com.company.rag.rag.retriever.impl.FullTextRetriever;
import com.company.rag.rag.retriever.impl.FuzzyRetriever;
import com.company.rag.rag.retriever.impl.VectorRetriever;
import com.company.rag.rag.service.MultiRetrieveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 多路检索服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiRetrieveServiceImpl implements MultiRetrieveService {
    
    private final VectorRetriever vectorRetriever;
    private final FullTextRetriever fullTextRetriever;
    private final FuzzyRetriever fuzzyRetriever;
    private final RankNormalizer normalizer;
    private final ResultFuser fuser;
    private final ResultFilter filter;
    
    @Override
    public List<RagResult.ChunkResult> retrieve(RagQuery query) {
        log.info("开始多路混合检索 | query={} | strategy={}", query.getQuery(), query.getRetrievalStrategy());
        
        // 1. 执行三路检索
        long start = System.currentTimeMillis();
        List<RagResult.ChunkResult> vectorResults = Collections.emptyList();
        List<RagResult.ChunkResult> fullTextResults = Collections.emptyList();
        List<RagResult.ChunkResult> fuzzyResults = Collections.emptyList();
        
        try {
            vectorResults = vectorRetriever.retrieve(query.getQuery(), 50);
            log.info("向量检索完成 | 数量={}", vectorResults.size());
        } catch (Exception e) {
            log.error("向量检索失败", e);
        }
        
        try {
            fullTextResults = fullTextRetriever.retrieve(query.getQuery(), 50);
            log.info("全文检索完成 | 数量={}", fullTextResults.size());
        } catch (Exception e) {
            log.error("全文检索失败", e);
        }
        
        try {
            fuzzyResults = fuzzyRetriever.retrieve(query.getQuery(), 30);
            log.info("模糊匹配完成 | 数量={}", fuzzyResults.size());
        } catch (Exception e) {
            log.error("模糊匹配失败", e);
        }
        
        // 2. 归一化
        List<NormalizedResult> normVector = normalizer.normalize(vectorResults);
        List<NormalizedResult> normFullText = normalizer.normalize(fullTextResults);
        List<NormalizedResult> normFuzzy = normalizer.normalize(fuzzyResults);
        
        // 3. 融合
        List<FusedResult> fused = fuser.fuse(normVector, normFullText, normFuzzy, query.getQuery());
        log.info("融合完成 | 总数量={}", fused.size());
        
        // 4. 筛选
        int fusionTopK = query.getFusionTopK() != null ? query.getFusionTopK() : 30;  // 默认 30，控制 Rerank 候选数
        List<FusedResult> filtered = filter.filter(fused, fusionTopK, query.getScoreThreshold());
        log.info("筛选完成 | 剩余数量={}", filtered.size());
        
        // 5. 返回融合结果（Rerank 由上层服务控制）
        List<RagResult.ChunkResult> reranked = new java.util.ArrayList<>(filtered);
        
        long elapsed = System.currentTimeMillis() - start;
        log.info("多路检索完成 | 总耗时={}ms | enableRerank={}", elapsed, query.getEnableRerank());
        
        return reranked;
    }
}
