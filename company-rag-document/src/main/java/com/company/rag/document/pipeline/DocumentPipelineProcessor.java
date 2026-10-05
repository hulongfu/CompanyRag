package com.company.rag.document.pipeline;

/**
 * 文档入库管道任务处理器。
 *
 * <p>由 {@link AsyncDocumentPipelineExecutor} 的 worker 线程调用，负责单条任务的完整分步流转
 * （状态推进 + 每步独立事务 + 租户恢复 + 重试）。具体流转逻辑由 T4 的实现
 * （{@code PipelineStepExecutorTemplate}）提供。</p>
 */
public interface DocumentPipelineProcessor {

    /**
     * 执行一条管道任务（阻塞直到终态或重试耗尽）。
     *
     * @param task 待执行任务
     */
    void processTask(PipelineTask task);
}