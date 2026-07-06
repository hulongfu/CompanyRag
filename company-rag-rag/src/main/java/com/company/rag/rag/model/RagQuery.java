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
    private Boolean stream = false;     // 是否流式
    private String sessionId;           // 会话ID（用于对话历史）
}
