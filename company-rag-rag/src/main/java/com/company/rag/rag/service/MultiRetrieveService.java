package com.company.rag.rag.service;

import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;

import java.util.List;

/**
 * 多路检索服务
 */
public interface MultiRetrieveService {
    /**
     * 执行多路混合检索
     * @param query 查询参数
     * @return 检索结果（已融合、筛选、Rerank）
     */
    List<RagResult.ChunkResult> retrieve(RagQuery query);
}
