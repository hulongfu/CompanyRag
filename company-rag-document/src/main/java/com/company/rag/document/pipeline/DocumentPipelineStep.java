package com.company.rag.document.pipeline;

/**
 * 文档入库管道单个步骤。
 *
 * <p>模板（{@code PipelineStepExecutorTemplate}）按固定顺序调用各步骤，每步独立落库。
 * 步骤失败抛异常即视为该步失败，由模板决定重试或置 FAILED。</p>
 */
public interface DocumentPipelineStep {

    /** 步骤名，与 document_pipeline_state.step/status 命名保持一致。 */
    String name();

    /**
     * 执行步骤业务逻辑。
     *
     * @param task 管道任务
     * @throws PipelineNotFoundAfterDelete 文档已被删除（终止并清理，不再重试）
     * @throws RuntimeException 该步失败（是否重试由模板决定）
     */
    void execute(PipelineTask task);
}