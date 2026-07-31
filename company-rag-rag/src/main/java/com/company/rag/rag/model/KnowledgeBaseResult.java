package com.company.rag.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

import static java.util.Collections.emptyList;

/**
 * 知识库 RAG 工具响应结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseResult {
    
    /**
     * 是否成功
     */
    private boolean success;
    
    /**
     * 答案内容
     */
    private String answer;
    
    /**
     * 引用来源列表
     */
    private List<Citation> citations;
    
    /**
     * 错误信息（失败时）
     */
    private String error;
    
    /**
     * 创建成功结果
     */
    public static KnowledgeBaseResult ok(String answer, List<Citation> citations) {
        return KnowledgeBaseResult.builder()
                .success(true)
                .answer(answer)
                .citations(citations)
                .build();
    }
    
    /**
     * 创建失败结果
     */
    public static KnowledgeBaseResult failed(String error) {
        return KnowledgeBaseResult.builder()
                .success(false)
                .error(error)
                .citations(emptyList())
                .build();
    }
    
    /**
     * 引用来源
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Citation {
        /**
         * 来源文件名
         */
        private String filename;
        
        /**
         * 内容片段（前 200 字符）
         */
        private String contentPreview;
        
        /**
         * 相似度分数
         */
        private double score;
        
        /**
         * Chunk 索引
         */
        private int chunkIndex;
    }
}
