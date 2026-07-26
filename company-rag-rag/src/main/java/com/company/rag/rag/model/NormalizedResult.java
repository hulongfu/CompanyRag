package com.company.rag.rag.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 归一化后的检索结果
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NormalizedResult extends RagResult.ChunkResult {
    
    /**
     * 原始结果
     */
    private RagResult.ChunkResult original;
    
    /**
     * 归一化后的分数 [0, 1]
     */
    private Double normalizedScore;
    
    public NormalizedResult() {
        super();
    }
    
    public NormalizedResult(RagResult.ChunkResult original) {
        super();
        this.original = original;
        // 复制原始字段
        this.setChunkId(original.getChunkId());
        this.setContent(original.getContent());
        this.setDocumentName(original.getDocumentName());
        this.setChunkIndex(original.getChunkIndex());
        this.setVectorScore(original.getVectorScore());
        this.setKeywordScore(original.getKeywordScore());
    }
}
