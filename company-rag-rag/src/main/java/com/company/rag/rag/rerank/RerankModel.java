package com.company.rag.rag.rerank;

import com.company.rag.rag.model.RerankResponse;
import java.util.List;

/**
 * 重排序模型接口
 */
public interface RerankModel {
    /**
     * 对文档列表进行重排序
     * 
     * @param query 查询文本
     * @param documents 待排序的文档列表
     * @param topN 返回前 N 个结果
     * @return 重排序结果
     */
    RerankResponse rerank(String query, List<String> documents, int topN);
}
