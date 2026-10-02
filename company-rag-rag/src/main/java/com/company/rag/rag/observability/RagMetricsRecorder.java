package com.company.rag.rag.observability;

import com.company.rag.rag.model.RagResult;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * RAG指标记录器 - Prometheus可观测性埋点
 * 追踪每次RAG请求的延迟、召回率、Token消耗
 */
@Slf4j
@Component
public class RagMetricsRecorder {

    private final MeterRegistry meterRegistry;

    // 延迟指标
    private final Timer ragTotalTimer;
    private final Timer retrievalTimer;
    private final Timer rerankTimer;
    private final Timer llmTimer;

    // 计数器
    private final Counter totalRequests;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter rateLimitHits;

    // 最近一次请求的召回率，供 rag.recall.rate Gauge 读取。
    // Micrometer 的 Gauge 对绑定对象是弱引用且同 MeterId 重复注册会被忽略，
    // 因此必须绑定本单例常驻对象、只在构造期注册一次，由每次请求刷新该字段。
    private volatile double lastRecallRate;

    public RagMetricsRecorder(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        this.ragTotalTimer = Timer.builder("rag.request.total")
                .description("RAG请求总耗时")
                .register(meterRegistry);
        this.retrievalTimer = Timer.builder("rag.retrieval.duration")
                .description("检索阶段耗时")
                .register(meterRegistry);
        this.rerankTimer = Timer.builder("rag.rerank.duration")
                .description("Rerank阶段耗时")
                .register(meterRegistry);
        this.llmTimer = Timer.builder("rag.llm.duration")
                .description("LLM调用耗时")
                .register(meterRegistry);

        this.totalRequests = Counter.builder("rag.requests.total")
                .description("RAG请求总数")
                .register(meterRegistry);
        this.cacheHits = Counter.builder("rag.cache.hits")
                .description("缓存命中次数")
                .register(meterRegistry);
        this.cacheMisses = Counter.builder("rag.cache.misses")
                .description("缓存未命中次数")
                .register(meterRegistry);
        this.rateLimitHits = Counter.builder("rag.ratelimit.hits")
                .description("限流触发次数")
                .register(meterRegistry);

        Gauge.builder("rag.recall.rate", this, RagMetricsRecorder::getLastRecallRate)
                .description("最近一次请求的召回率")
                .register(meterRegistry);
    }

    public double getLastRecallRate() {
        return lastRecallRate;
    }

    /**
     * 记录一次RAG请求的完整指标
     */
    public void record(RagResult result) {
        totalRequests.increment();

        if (result.getMetrics() != null) {
            var m = result.getMetrics();
            ragTotalTimer.record(m.getTotalMs(), TimeUnit.MILLISECONDS);
            retrievalTimer.record(m.getRetrievalMs(), TimeUnit.MILLISECONDS);
            rerankTimer.record(m.getRerankMs(), TimeUnit.MILLISECONDS);
            llmTimer.record(m.getLlmMs(), TimeUnit.MILLISECONDS);

            // 记录Token消耗
            meterRegistry.counter("rag.tokens.input",
                    "model", "qwen-max").increment(m.getInputTokens());
            meterRegistry.counter("rag.tokens.output",
                    "model", "qwen-max").increment(m.getOutputTokens());
            meterRegistry.counter("rag.tokens.total",
                    "model", "qwen-max").increment(m.getInputTokens() + m.getOutputTokens());

            // 记录召回率：Gauge 已在构造期注册，此处只刷新取值，避免重复注册被忽略
            this.lastRecallRate = m.getRecallRate();
        }

        // 记录检索到的文档块数量
        meterRegistry.counter("rag.chunks.retrieved",
                "tenant", String.valueOf(result.getChunks() != null ? result.getChunks().size() : 0))
                .increment();
    }

    public void recordCacheHit() { cacheHits.increment(); }
    public void recordCacheMiss() { cacheMisses.increment(); }
    public void recordRateLimitHit() { rateLimitHits.increment(); }
}
