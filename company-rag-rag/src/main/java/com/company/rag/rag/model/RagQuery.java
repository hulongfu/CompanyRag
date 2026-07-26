package com.company.rag.rag.model;

import lombok.Data;

import java.util.List;

/**
 * RAG查询参数
 */
@Data
public class RagQuery {
    private Long tenantId;
    private String query;               // 用户问题
    private List<String> documentIds;   // 限定文档范围（可选）
    private Integer topK = 10;          // 检索返回条数
    private Integer rerankTopK = 5;     // Rerank后保留条数
    private Double vectorWeight = 0.5;  // 向量检索权重（混合检索用）
    private Double keywordWeight = 0.5; // 关键词检索权重
    private Boolean enableRerank = true;
    private Long userId;                // 用户ID
    private Boolean stream = false;     // 是否流式
    private String sessionId;           // 会话 ID（用于对话历史）
    
    /**
     * 检索策略
     * HYBRID: 多路混合检索（默认）
     * VECTOR_ONLY: 仅向量检索
     * FULLTEXT_ONLY: 仅全文检索
     */
    private String retrievalStrategy = "HYBRID";
    
    /**
     * 融合后进入 Rerank 的候选数（默认 50）
     */
    private Integer fusionTopK = 50;
    
    /**
     * 分数阈值（默认 0.3）
     */
    private Double scoreThreshold = 0.3;
}
