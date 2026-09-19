package com.company.rag.document.pipeline;

/**
 * 文档入库管道分步状态枚举（对应 document_pipeline_state.status）。
 *
 * <p>承载比 rag_document.status(0/1/2/-1) 更细的分段进度，
 * 供状态查询与崩溃恢复续跑使用。非终态为：PENDING/PARSING/CHUNKING/RAG_INGEST/VECTORIZING；终态为：SUCCESS/FAILED。</p>
 */
public enum PipelineStatus {

    /** 已受理，等待执行 */
    PENDING,
    /** 解析中（Tika 文本提取） */
    PARSING,
    /** 切分中（生成 doc_chunk） */
    CHUNKING,
    /** chunk 落库中 */
    RAG_INGEST,
    /** 向量化中（PGVector 入库） */
    VECTORIZING,
    /** 成功（终态） */
    SUCCESS,
    /** 失败（终态） */
    FAILED;

    /** 是否为非终态（崩溃恢复需扫描的状态）。 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED;
    }
}