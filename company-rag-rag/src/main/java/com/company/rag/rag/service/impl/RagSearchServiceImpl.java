package com.company.rag.rag.service.impl;

import com.company.rag.common.constant.RagConstant;
import com.company.rag.common.exception.BizException;
import com.company.rag.rag.cache.RagCacheManager;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.observability.RagMetricsRecorder;
import com.company.rag.rag.prompt.PromptTemplate;
import com.company.rag.rag.rerank.CrossEncoderReranker;
import com.company.rag.rag.service.RagSearchService;
import com.company.rag.rag.service.RagSessionService;
import com.company.rag.rag.entity.RagSessionMeta;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagSearchServiceImpl implements RagSearchService {

    private final VectorStore vectorStore;
    private final OpenAiChatModel chatModel;
    private final CrossEncoderReranker reranker;
    private final RagCacheManager cacheManager;
    private final RagMetricsRecorder metricsRecorder;
    private final PromptTemplate promptTemplate;

    @Override
    @CircuitBreaker(name = "rag", fallbackMethod = "searchFallback")
    @RateLimiter(name = "rag-rate-limiter", fallbackMethod = "searchFallback")
    public RagResult search(RagQuery query) {
        long start = System.currentTimeMillis();
        // 1. 检查缓存
        String cacheKey = buildCacheKey(query);
        RagResult cached = cacheManager.getSearchResult(cacheKey);
        if (cached != null) {
            log.info("RAG缓存命中: key={}", cacheKey);
            metricsRecorder.recordCacheHit();
            return cached;
        }

        // 2. 混合检索
        long retrievalStart = System.currentTimeMillis();
        List<RagResult.ChunkResult> chunks = hybridRetrieve(query);
        long retrievalMs = System.currentTimeMillis() - retrievalStart;

        // 3. Rerank
        long rerankStart = System.currentTimeMillis();
        if (query.getEnableRerank() && !chunks.isEmpty()) {
            chunks = reranker.rerank(query.getQuery(), chunks, query.getRerankTopK());
        }
        long rerankMs = System.currentTimeMillis() - rerankStart;

        // 4. 构建Prompt并调用LLM
        long llmStart = System.currentTimeMillis();
        String context = chunks.stream()
                .map(c -> "[来源:" + c.getDocumentName() + "] " + c.getContent())
                .collect(Collectors.joining("\n\n"));
        String prompt = promptTemplate.buildChatPrompt(query.getQuery(), context);
        String answer = chatModel.call(prompt);
        long llmMs = System.currentTimeMillis() - llmStart;

        // 5. 组装结果
        RagResult result = new RagResult();
        result.setAnswer(answer);
        result.setChunks(chunks);
        result.setSessions(chunks.stream()
                .map(c -> c.getDocumentName() + " (第" + c.getChunkIndex() + "段)")
                .collect(Collectors.toList()));

        RagResult.Metrics metrics = new RagResult.Metrics();
        metrics.setRetrievalMs(retrievalMs);
        metrics.setRerankMs(rerankMs);
        metrics.setLlmMs(llmMs);
        metrics.setTotalMs(System.currentTimeMillis() - start);
        result.setMetrics(metrics);

        // 6. 缓存结果
        cacheManager.putSearchResult(cacheKey, result);
        // 7. 记录指标
        metricsRecorder.record(result);
        metricsRecorder.recordCacheMiss();
        return result;
    }

    /**
     * RAG搜索降级方法
     */
    public RagResult searchFallback(RagQuery query, Throwable t) {
        log.warn("RAG搜索降级 | 原因: {}", t.getMessage());
        
        RagResult result = new RagResult();
        result.setAnswer("服务暂时繁忙，请稍后重试。");
        result.setChunks(Collections.emptyList());
        result.setSessions(Collections.emptyList());
        
        RagResult.Metrics metrics = new RagResult.Metrics();
        metrics.setTotalMs(0L);
        result.setMetrics(metrics);
        
        return result;
    }

    /**
     * 检索降级方法
     */
    public List<RagResult.ChunkResult> retrieveFallback(RagQuery query, Throwable t) {
        log.warn("RAG检索降级 | 原因: {}", t.getMessage());
        return Collections.emptyList();
    }

    @Override
    @CircuitBreaker(name = "rag", fallbackMethod = "streamFallback")
    @RateLimiter(name = "rag-rate-limiter", fallbackMethod = "streamFallback")
    public Flux<String> streamAnswer(RagQuery query) {
        long start = System.currentTimeMillis();
        // 先检索（受熔断限流保护）
        List<RagResult.ChunkResult> chunks = retrieve(query);
        if (query.getEnableRerank() && !chunks.isEmpty()) {
            chunks = reranker.rerank(query.getQuery(), chunks, query.getRerankTopK());
        }
        String context = chunks.stream()
                .map(c -> "[来源:" + c.getDocumentName() + "] " + c.getContent())
                .collect(Collectors.joining("\n\n"));
        String prompt = promptTemplate.buildChatPrompt(query.getQuery(), context);

        // 流式调用LLM（受熔断限流保护）
        long llmStart = System.currentTimeMillis();
        Flux<String> llmStream = chatModel.stream(prompt)
                .doOnComplete(() -> {
                    long llmMs = System.currentTimeMillis() - llmStart;
                    log.debug("流式回答LLM调用完成 | 耗时={}ms", llmMs);
                    // 记录指标
                    RagResult.Metrics metrics = new RagResult.Metrics();
                    metrics.setTotalMs(System.currentTimeMillis() - start);
                    metrics.setLlmMs(llmMs);
                    RagResult result = new RagResult();
                    result.setMetrics(metrics);
                    metricsRecorder.record(result);
                })
                .doOnError(e -> log.warn("流式回答LLM调用异常: {}", e.getMessage()));

        // 使用timeout包装，防止LLM调用hang住（30秒超时）
        return llmStream.timeout(java.time.Duration.ofSeconds(30))
                .onErrorResume(e -> {
                    log.warn("流式回答LLM调用超时或异常: {}", e.getMessage());
                    return Flux.just("服务暂时繁忙，请稍后重试。");
                });
    }

    /**
     * 流式回答降级方法
     */
    public Flux<String> streamFallback(RagQuery query, Throwable t) {
        log.warn("流式回答降级 | 原因: {}", t.getMessage());
        return Flux.just("服务暂时繁忙，请稍后重试。");
    }

    @Override
    @CircuitBreaker(name = "rag", fallbackMethod = "retrieveFallback")
    @RateLimiter(name = "rag-rate-limiter", fallbackMethod = "retrieveFallback")
    public List<RagResult.ChunkResult> retrieve(RagQuery query) {
        List<RagResult.ChunkResult> chunks = hybridRetrieve(query);
        if (query.getEnableRerank() && !chunks.isEmpty()) {
            chunks = reranker.rerank(query.getQuery(), chunks, query.getRerankTopK());
        }
        return chunks;
    }

    /**
     * 混合检索：向量检索 + 关键词检索（加权融合）
     */
    private List<RagResult.ChunkResult> hybridRetrieve(RagQuery query) {
        // 向量检索
        SearchRequest request = SearchRequest.builder()
                .query(query.getQuery())
                .topK(query.getTopK())
                .similarityThreshold(0.5)
                .build();
        var vectorResults = vectorStore.similaritySearch(request);

        // 将向量结果转换为 ChunkResult
        // 注意：实际项目中需从 VectorStore 返回的 Document 中提取 metadata
        List<RagResult.ChunkResult> allChunks = new ArrayList<>();
        for (var doc : vectorResults) {
            RagResult.ChunkResult cr = new RagResult.ChunkResult();
            cr.setChunkId(doc.getId() != null ? doc.getId() : "");
            cr.setContent(doc.getText());
            cr.setVectorScore(doc.getMetadata() != null ?
                    ((Number) doc.getMetadata().getOrDefault("distance", 0.0)).doubleValue() : 0.0);
            cr.setDocumentName(doc.getMetadata() != null ?
                    (String) doc.getMetadata().getOrDefault("documentName", "未知") : "未知");
            // 设置 chunkIndex（从 metadata 中获取）
            Object chunkIndexObj = doc.getMetadata() != null ? doc.getMetadata().get("chunkIndex") : null;
            if (chunkIndexObj != null) {
                cr.setChunkIndex(((Number) chunkIndexObj).intValue());
            }
            cr.setFinalScore(cr.getVectorScore());
            allChunks.add(cr);
        }
        
        // 诊断日志：打印每个 chunk 的简要信息
        log.info("转换为 ChunkResult 完成 | 数量={} | 前 3 个 chunk 内容预览：{}", allChunks.size(),
                allChunks.stream().limit(3).map(c -> 
                    String.format("[文档:%s, chunkIndex:%s, 内容:%s...]", 
                        c.getDocumentName(), 
                        c.getChunkIndex(), 
                        c.getContent() != null && c.getContent().length() > 30 ? c.getContent().substring(0, 30) : "N/A")
                ).collect(Collectors.joining(" | ")));

        // 关键词检索增强（简单实现：用BM25风格评分）
        // 实际项目中可集成Elasticsearch或PostgreSQL全文检索
        String[] queryTerms = query.getQuery().toLowerCase().split("\s+");
        for (RagResult.ChunkResult chunk : allChunks) {
            double keywordScore = computeKeywordScore(chunk.getContent(), queryTerms);
            chunk.setKeywordScore(keywordScore);
            // 加权融合
            chunk.setFinalScore(
                    query.getVectorWeight() * (chunk.getVectorScore() != null ? chunk.getVectorScore() : 0)
                            + query.getKeywordWeight() * keywordScore);
        }
        // 按最终得分排序
        allChunks.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));
        return allChunks;
    }

    /**
     * 简单关键词匹配评分
     */
    private double computeKeywordScore(String content, String[] terms) {
        String lower = content.toLowerCase();
        int matchCount = 0;
        for (String term : terms) {
            if (lower.contains(term)) matchCount++;
        }
        return terms.length > 0 ? (double) matchCount / terms.length : 0;
    }

    private String buildCacheKey(RagQuery query) {
        // 使用租户ID + 查询文本作为缓存键，避免hashCode()冲突
        return RagConstant.CACHE_DOC_VECTOR + query.getTenantId() + ":" + 
               query.getQuery().trim().toLowerCase();
    }
}
