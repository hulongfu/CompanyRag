package com.company.rag.rag.retriever;

import com.company.rag.rag.model.MultiRetrieveResult;
import com.company.rag.rag.model.RagQuery;

/**
 * 多路检索器接口
 */
public interface MultiRetriever {
    /**
     * 执行多路检索
     * @param query 用户查询
     * @return 三路检索结果
     */
    MultiRetrieveResult retrieve(RagQuery query);
}
