package com.company.rag.document.splitter;

import com.company.rag.document.entity.DocumentChunk;
import java.util.List;

/**
 * 文档切分器接口
 */
public interface DocumentSplitter {

    /**
     * 将文本切分为块
     * @param text 原始文本
     * @param chunkSize 块大小（字符数或Token数）
     * @param chunkOverlap 块重叠
     * @return 切分结果
     */
    List<DocumentChunk> split(String text, int chunkSize, int chunkOverlap);

    /**
     * 获取切分策略
     */
    SplitStrategy getStrategy();
}
