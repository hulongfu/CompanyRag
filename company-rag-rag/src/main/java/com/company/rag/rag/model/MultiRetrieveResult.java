package com.company.rag.rag.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 多路检索结果容器
 */
@Data
public class MultiRetrieveResult {
    /**
     * 向量检索结果
     */
    private List<RagResult.ChunkResult> vectorResults = new ArrayList<>();
    
    /**
     * 全文检索结果
     */
    private List<RagResult.ChunkResult> fullTextResults = new ArrayList<>();
    
    /**
     * 模糊匹配结果
     */
    private List<RagResult.ChunkResult> fuzzyResults = new ArrayList<>();
}
