package com.company.rag.rag.model;

import lombok.Data;

import java.util.List;

/**
 * RAG检索结果
 */
@Data
public class RagResult {
    private String answer;              // LLM生成的回答
    private List<ChunkResult> chunks;   // 引用的文档块
    private List<String> sessions;      // 引用来源
    private Metrics metrics;            // 性能指标

    @Data
    public static class ChunkResult {
        private Long chunkId;
        private Long documentId;
        private String documentName;
        private String content;
        private Double vectorScore;     // 向量检索得分
        private Double keywordScore;    // 关键词检索得分
        private Double rerankScore;     // Rerank得分
        private Double finalScore;      // 最终得分
        private Integer chunkIndex;
    }

    @Data
    public static class Metrics {
        private long retrievalMs;       // 检索耗时
        private long rerankMs;          // 重排序耗时
        private long llmMs;             // LLM调用耗时
        private long totalMs;           // 总耗时
        private int inputTokens;        // 输入Token数
        private int outputTokens;       // 输出Token数
        private double recallRate;      // 召回率
    }
}
