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
    private Integer rerankTopK = 20;    // Rerank 后保留条数（修改为 20，避免瓶颈平移）
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
     * 融合后进入 Rerank 的候选数（默认 30，控制 HTTP 请求风险）
     */
    private Integer fusionTopK = 30;
    
    /**
     * 分数阈值（默认 0.3）
     */
    private Double scoreThreshold = 0.3;
    
    /**
     * 多样性去重：每文档最多保留条数（默认 3）
     * 用于 Rerank 后的最终筛选，避免同一文档占据过多位置
     */
    private Integer maxPerDoc = 3;
}
