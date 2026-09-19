package com.company.rag.document.pipeline;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 文档入库管道分步重试器。
 *
 * <p>对可重试步骤（切分/落库/向量化）用 Resilience4j Retry 包装，失败重试 N 次并加固定间隔。
 * 解析（Tika）与删除竞态不重试。各步骤的 Retry 实例按步骤名缓存。</p>
 */
@Component
public class DocumentPipelineRecover {

    /** 每步失败重试的初始间隔（毫秒）。 */
    @Value("${document.pipeline.retry.interval:1000}")
    private long retryIntervalMs;

    /** 切分/落库类步骤最大重试次数（不含首次）。 */
    @Value("${document.pipeline.retry.max-attempts:2}")
    private int defaultMaxAttempts;

    /** 向量化步骤最大重试次数（向量化更易临时失败，放宽）。 */
    @Value("${document.pipeline.retry.vectorize-max-attempts:3}")
    private int vectorizeMaxAttempts;

    private final ConcurrentHashMap<String, Retry> retryCache = new ConcurrentHashMap<>();

    /**
     * 对指定步骤执行并重试。
     *
     * <p>默认策略覆盖除向量化外的可重试步骤；向量化步骤单独传 true 使用更宽松策略。
     * 解析与删除竞态不调用本方法。</p>
     *
     * @param stepName    步骤名
     * @param vectorize   是否为向量化步骤
     * @param action      要重试的动作
     * @param <T>         返回类型
     * @return action 的返回值
     */
    public <T> T executeWithRetry(String stepName, boolean vectorize, Supplier<T> action) {
        Retry retry = retryCache.computeIfAbsent(stepName,
                s -> newRetry(vectorize ? vectorizeMaxAttempts : defaultMaxAttempts));
        return Retry.decorateSupplier(retry, action).get();
    }

    private Retry newRetry(int maxAttempts) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(retryIntervalMs))
                .build();
        return Retry.of("pipeline-" + retryCache.size(), config);
    }
}