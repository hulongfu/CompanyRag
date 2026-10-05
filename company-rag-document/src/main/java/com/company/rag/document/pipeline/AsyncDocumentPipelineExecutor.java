package com.company.rag.document.pipeline;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 文档入库管道异步执行器。
 *
 * <p>任务提交到 {@link DocumentPipelineExecutor} 线程池执行，processor 负责分步流转。</p>
 *
 * <ul>
 *   <li>{@code submit} 入队后立刻返回（不阻塞），队列满时由线程池的 AbortPolicy 抛异常。</li>
 *   <li>{@code @PreDestroy} 优雅关闭：拒绝新任务，等待在跑/排队任务完成后关闭，避免进程退出时丢任务。</li>
 * </ul>
 */
@Slf4j
@Component
public class AsyncDocumentPipelineExecutor {

    private final ThreadPoolTaskExecutor executor;
    private final DocumentPipelineProcessor processor;

    public AsyncDocumentPipelineExecutor(
            @Qualifier("documentPipelineExecutor") ThreadPoolTaskExecutor executor,
            DocumentPipelineProcessor processor) {
        this.executor = executor;
        this.processor = processor;
    }

    /**
     * 提交一条管道任务异步执行。
     *
     * @param task 任务载荷（上传请求线程构造）
     * @throws java.util.concurrent.RejectedExecutionException 线程池队列满时
     */
    public void submit(PipelineTask task) {
        executor.submit(() -> {
            try {
                processor.processTask(task);
            } catch (Throwable t) {
                // 处理器内部已兜底到终态；此处仅为防止未被捕获的异常导致线程池日志污染
                log.error("处理文档管道任务失败 | taskId={} | documentId={} | error={}",
                        task.getTaskId(), task.getDocumentId(), t.getMessage(), t);
            }
        });
    }

    /**
     * 优雅关闭：拒绝新任务并等待已提交任务执行完毕。
     * 应用退出时调用，避免异步管道在进程关闭瞬间丢失在跑任务。
     */
    @PreDestroy
    public void shutdown() {
        log.info("关闭文档管道线程池，等待在跑任务完成");
        executor.shutdown();
    }
}