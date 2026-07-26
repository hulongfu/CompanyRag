package com.company.rag.rag.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 融合后的检索结果
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FusedResult extends NormalizedResult {
    
    /**
     * 最终融合分数
     */
    private Double finalScore = 0.0;
    
    /**
     * 文档 ID（用于去重）
     */
    private String documentId;
    
    public FusedResult() {
        super();
    }
    
    public FusedResult(NormalizedResult original) {
        super(original);
        this.original = original.original;
        this.normalizedScore = original.normalizedScore;
    }
    
    /**
     * 添加某路检索的加权分数
     */
    public void addScore(double weight, double score) {
        this.finalScore += weight * score;
    }
}
