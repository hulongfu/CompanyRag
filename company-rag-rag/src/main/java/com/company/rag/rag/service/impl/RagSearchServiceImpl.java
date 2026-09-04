package com.company.rag.rag.service.impl;

import com.company.rag.common.constant.RagConstant;
import com.company.rag.common.exception.BizException;
import com.company.rag.rag.cache.RagCacheManager;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.observability.RagMetricsRecorder;
import com.company.rag.rag.prompt.PromptTemplate;
import com.company.rag.rag.rerank.CrossEncoderReranker;
import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.retriever.impl.FullTextRetriever;
import com.company.rag.rag.retriever.impl.VectorRetriever;
import com.company.rag.rag.service.MultiRetrieveService;
import com.company.rag.rag.service.RagSearchService;
import com.company.rag.rag.service.RagSessionService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagSearchServiceImpl implements RagSearchService {

    private final VectorStore vectorStore;
    private final ObjectProvider<OpenAiChatModel> chatModelProvider;
    private final CrossEncoderReranker reranker;
    private final ResultFilter resultFilter;
    private final RagCacheManager cacheManager;
    private final RagMetricsRecorder metricsRecorder;
    private final PromptTemplate promptTemplate;
    private final RagSessionService ragSessionService;
    private final MultiRetrieveService multiRetrieveService;
    private final VectorRetriever vectorRetriever;
    private final FullTextRetriever fullTextRetriever;

    @Override
    @CircuitBreaker(name = "rag", fallbackMethod = "searchFallback")
    @RateLimiter(name = "rag-rate-limiter", fallbackMethod = "searchFallback")
    public RagResult search(RagQuery query) {
        long start = System.currentTimeMillis();
        // 1. 检查缓存
        String cacheKey = buildCacheKey(query);
        RagResult cached = cacheManager.getSearchResult(cacheKey);
        if (cached != null) {
            log.info("RAG 缓存命中：key={}", cacheKey);
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
        // finalFilter 无条件执行（与 enableRerank 无关），按文档分组每组最多 maxPerDoc 条
        chunks = resultFilter.finalFilter(chunks, query.getTopK(), query.getMaxPerDoc());
        long rerankMs = System.currentTimeMillis() - rerankStart;

        // 4. 构建 Prompt 并调用 LLM
        long llmStart = System.currentTimeMillis();
        String context = chunks.stream()
                .map(c -> {
                    String name = c.getDocumentName() != null ? c.getDocumentName() : "未知";
                    return "[来源:" + name + "] " + c.getContent();
                })
                .collect(Collectors.joining("\n\n"));
        String prompt = promptTemplate.buildChatPrompt(query.getQuery(), context);
        String answer = chatModelProvider.getObject().call(prompt);
        long llmMs = System.currentTimeMillis() - llmStart;

        // 5. 组装结果
        RagResult result = new RagResult();
        result.setAnswer(answer);
        result.setChunks(chunks);
        result.setSessions(chunks.stream()
                .map(c -> {
                    String name = c.getDocumentName() != null ? c.getDocumentName() : "未知";
                    if (c.getChunkIndex() != null) {
                        return name + " (第" + c.getChunkIndex() + "段)";
                    } else {
                        return name;
                    }
                })
                .collect(Collectors.toList()));

        RagResult.Metrics metrics = new RagResult.Metrics();
        metrics.setRetrievalMs(retrievalMs);
        metrics.setRerankMs(rerankMs);
        metrics.setLlmMs(llmMs);
        metrics.setTotalMs(System.currentTimeMillis() - start);
        result.setMetrics(metrics);

        // 6. 保存对话记录（如果有 sessionId）
        if (query.getSessionId() != null) {
            try {
                Long userId = query.getUserId() != null ? query.getUserId() : 1L;
                ragSessionService.saveConversation(
                        query.getTenantId(), query.getSessionId(), userId,
                        query.getQuery(), answer, context,
                        0, 0, (int) (System.currentTimeMillis() - start));
            } catch (Exception e) {
                log.warn("保存对话记录失败", e);
            }
        }

        // 7. 缓存结果
        cacheManager.putSearchResult(cacheKey, result);
        // 8. 记录指标
        metricsRecorder.record(result);
        metricsRecorder.recordCacheMiss();
        return result;
    }

    /**
     * RAG 搜索降级方法
     */
    public RagResult searchFallback(RagQuery query, Throwable t) {
        log.warn("RAG 搜索降级 | 原因：{}", t.getMessage());
        
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
        log.warn("RAG 检索降级 | 原因：{}", t.getMessage());
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
        // finalFilter 无条件执行（与 enableRerank 无关），按文档分组每组最多 maxPerDoc 条
        chunks = resultFilter.finalFilter(chunks, query.getTopK(), query.getMaxPerDoc());
        String context = chunks.stream()
                .map(c -> "[来源:" + c.getDocumentName() + "] " + c.getContent())
                .collect(Collectors.joining("\n\n"));
        String prompt = promptTemplate.buildChatPrompt(query.getQuery(), context);

        // 流式调用 LLM（受熔断限流保护）
        long llmStart = System.currentTimeMillis();
        Flux<String> llmStream = chatModelProvider.getObject().stream(prompt)
                .doOnComplete(() -> {
                    long llmMs = System.currentTimeMillis() - llmStart;
                    log.debug("流式回答 LLM 调用完成 | 耗时={}ms", llmMs);
                    // 记录指标
                    RagResult.Metrics metrics = new RagResult.Metrics();
                    metrics.setTotalMs(System.currentTimeMillis() - start);
                    metrics.setLlmMs(llmMs);
                    RagResult result = new RagResult();
                    result.setMetrics(metrics);
                    metricsRecorder.record(result);
                })
                .doOnError(e -> log.warn("流式回答 LLM 调用异常：{}", e.getMessage()));

        // 使用 timeout 包装，防止 LLM 调用 hang 住（30 秒超时）
        return llmStream.timeout(java.time.Duration.ofSeconds(30))
                .onErrorResume(e -> {
                    log.warn("流式回答 LLM 调用超时或异常：{}", e.getMessage());
                    return Flux.just("服务暂时繁忙，请稍后重试。");
                });
    }

    /**
     * 流式回答降级方法
     */
    public Flux<String> streamFallback(RagQuery query, Throwable t) {
        log.warn("流式回答降级 | 原因：{}", t.getMessage());
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
        // finalFilter 无条件执行（与 enableRerank 无关），按文档分组每组最多 maxPerDoc 条
        chunks = resultFilter.finalFilter(chunks, query.getTopK(), query.getMaxPerDoc());
        return chunks;
    }

    /**
     * 混合检索：委托给多路检索服务
     */
    private List<RagResult.ChunkResult> hybridRetrieve(RagQuery query) {
        // 根据检索策略选择检索方式
        if ("VECTOR_ONLY".equalsIgnoreCase(query.getRetrievalStrategy())) {
            // 降级为仅向量检索
            return vectorRetriever.retrieve(query.getQuery(), query.getTopK());
        } else if ("FULLTEXT_ONLY".equalsIgnoreCase(query.getRetrievalStrategy())) {
            // 降级为仅全文检索
            return fullTextRetriever.retrieve(query.getQuery(), query.getTopK());
        } else {
            // 默认：多路混合检索
            return multiRetrieveService.retrieve(query);
        }
    }

    private String buildCacheKey(RagQuery query) {
        // 使用租户 ID + 查询文本 + 检索参数作为缓存键，避免不同参数组合错误命中缓存
        // 格式：company:rag:vector:{tenantId}:{query}:{topK}:{strategy}:{rerank}:{rerankTopK}:{maxPerDoc}:{fusionTopK}:{scoreThreshold}
        String normalizedQuery = query.getQuery().trim().toLowerCase();
        String strategy = query.getRetrievalStrategy() != null ? query.getRetrievalStrategy() : "HYBRID";
        int topK = query.getTopK() != null ? query.getTopK() : 10;
        boolean enableRerank = query.getEnableRerank() != null && query.getEnableRerank();
        int rerankTopK = query.getRerankTopK() != null ? query.getRerankTopK() : 20;
        int maxPerDoc = query.getMaxPerDoc() != null ? query.getMaxPerDoc() : 3;
        int fusionTopK = query.getFusionTopK() != null ? query.getFusionTopK() : 30;      // 新增参数
        // scoreThreshold 为 null 时表示不启用硬阈值，需用独立标识，避免与显式传入的任意有效值（含 0.3）互相错误命中缓存
        String scoreThreshold = query.getScoreThreshold() != null
                ? String.valueOf(query.getScoreThreshold()) : "null";
        
        return RagConstant.CACHE_DOC_VECTOR + query.getTenantId() + ":" + 
               normalizedQuery + ":" + topK + ":" + strategy + ":" + 
               (enableRerank ? "1" : "0") + ":" + rerankTopK + ":" + maxPerDoc + ":" + 
               fusionTopK + ":" + scoreThreshold;
    }
}
