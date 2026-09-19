package com.company.rag.document.pipeline;

/**
 * 文档删除竞态信号：步骤执行中发现 rag_document 对应记录已被删除。
 *
 * <p>模板捕获 {@code NotFoundAfterDelete} 后终止任务并清理 document_pipeline_state，
 * 不再重试（文档已删，重跑无意义）。</p>
 */
public class PipelineNotFoundAfterDelete extends RuntimeException {

    public PipelineNotFoundAfterDelete(String message) {
        super(message);
    }
}