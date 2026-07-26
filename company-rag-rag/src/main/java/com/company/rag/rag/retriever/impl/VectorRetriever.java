package com.company.rag.rag.retriever.impl;

import com.company.rag.rag.model.RagResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量检索器（复用现有 VectorStore）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorRetriever {
    
    private final VectorStore vectorStore;
    
    /**
     * 向量检索
     * @param query 查询文本
     * @param topK 返回数量
     * @return 检索结果
     */
    public List<RagResult.ChunkResult> retrieve(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .similarityThreshold(0.0)  // 不过滤，后续归一化处理
            .build();
        
        List<Document> docs = vectorStore.similaritySearch(request);
        return convertToChunkResults(docs);
    }
    
    private List<RagResult.ChunkResult> convertToChunkResults(List<Document> docs) {
        List<RagResult.ChunkResult> results = new ArrayList<>();
        for (Document doc : docs) {
            RagResult.ChunkResult cr = new RagResult.ChunkResult();
            cr.setChunkId(doc.getId() != null ? doc.getId() : "");
            cr.setContent(doc.getText());
            cr.setVectorScore(doc.getMetadata() != null ?
                ((Number) doc.getMetadata().getOrDefault("distance", 0.0)).doubleValue() : 0.0);
            cr.setDocumentName(doc.getMetadata() != null ?
                (String) doc.getMetadata().getOrDefault("documentName", "未知") : "未知");
            
            Object chunkIndexObj = doc.getMetadata() != null ? doc.getMetadata().get("chunkIndex") : null;
            if (chunkIndexObj != null) {
                cr.setChunkIndex(((Number) chunkIndexObj).intValue());
            }
            
            results.add(cr);
        }
        return results;
    }
}
