package com.company.rag.document.splitter;

import com.company.rag.document.entity.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定大小切分器 - 基础方案
 * 按固定字符数切分，无重叠
 */
@Component
public class FixedSizeSplitter implements DocumentSplitter {

    @Override
    public List<DocumentChunk> split(String text, int chunkSize, int chunkOverlap) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            DocumentChunk chunk = new DocumentChunk();
            chunk.setChunkIndex(index++);
            chunk.setContent(text.substring(start, end));
            chunk.setTokenCount(estimateTokens(chunk.getContent()));
            chunk.setSplitStrategy(getStrategy().name());
            chunks.add(chunk);
            start = end;
        }
        return chunks;
    }

    @Override
    public SplitStrategy getStrategy() {
        return SplitStrategy.FIXED_SIZE;
    }

    protected int estimateTokens(String text) {
        // 粗略估算：中文约1.5字/token，英文约4字符/token
        int chineseCount = 0;
        int asciiCount = 0;
        for (char c : text.toCharArray()) {
            if (c > 0x4E00 && c < 0x9FFF) chineseCount++;
            else if (c < 128) asciiCount++;
        }
        return (int)(chineseCount / 1.5 + asciiCount / 4.0 + 1);
    }
}
