package com.company.rag.rag.service;

import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG检索服务接口
 */
public interface RagSearchService {

    /**
     * 执行RAG检索（混合检索 + Rerank）
     */
    RagResult search(RagQuery query);

    /**
     * 流式RAG回答
     */
    Flux<String> streamAnswer(RagQuery query);

    /**
     * 仅检索文档块（不调用LLM）
     */
    List<RagResult.ChunkResult> retrieve(RagQuery query);
}
