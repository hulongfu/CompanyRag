package com.company.rag.document.pipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 文档入库管道配置。
 *
 * <p>定义异步处理线程池及相关参数，从 {@code document.pipeline.*} 读取。</p>
 * <ul>
 *   <li>队列满时采用 AbortPolicy：抛异常即 fail-fast，避免无限积压任务拖垮内存。</li>
 *   <li>注意：worker 线程自身的线程名带 {@code pipeline-} 前缀，便于压测与排障区分。</li>
 * </ul>
 */
@Configuration
public class DocumentPipelineConfig {

    @Value("${document.pipeline.core-pool-size:4}")
    private int corePoolSize;

    @Value("${document.pipeline.max-pool-size:8}")
    private int maxPoolSize;

    @Value("${document.pipeline.queue-capacity:100}")
    private int queueCapacity;

    /**
     * 文档处理线程池。
     *
     * @return 线程池执行器
     */
    @Bean(name = "documentPipelineExecutor")
    public ThreadPoolTaskExecutor documentPipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("pipeline-");
        // 队列满时拒绝提交并抛异常（fail-fast），由调用方感知任务未受理
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}