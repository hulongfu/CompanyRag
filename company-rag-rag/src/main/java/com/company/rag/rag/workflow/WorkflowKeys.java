package com.company.rag.rag.workflow;

/**
 * 混合检索工作流的图状态键常量。
 *
 * <p>由各检索节点、融合节点、筛选节点以及 HybridRetrievalWorkflow 共享，
 * 统一图状态读写所用的键名，避免魔法字符串散落。</p>
 */
public final class WorkflowKeys {

    /** 状态键：用户查询参数。 */
    public static final String QUERY = "query";
    /** 状态键：租户上下文快照（用于跨线程传播租户信息）。 */
    public static final String TENANT_CONTEXT = "tenantContext";
    /** 状态键：向量检索结果。 */
    public static final String VECTOR_CHUNKS = "vectorChunks";
    /** 状态键：全文检索结果。 */
    public static final String FULLTEXT_CHUNKS = "fullTextChunks";
    /** 状态键：模糊检索结果。 */
    public static final String FUZZY_CHUNKS = "fuzzyChunks";
    /** 状态键：归一化融合结果。 */
    public static final String FUSED = "fused";
    /** 状态键：最终筛选结果（工作流输出）。 */
    public static final String FILTERED = "filtered";

    private WorkflowKeys() {
    }
}