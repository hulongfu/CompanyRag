package com.company.rag.document.splitter;

import com.company.rag.document.entity.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 滑动窗口切分器 - 带重叠的改进方案
 * 通过重叠窗口保留上下文边界信息，减少信息断裂
 */
@Component
public class SlidingWindowSplitter extends FixedSizeSplitter {

    @Override
    public List<DocumentChunk> split(String text, int chunkSize, int chunkOverlap) {
        // 应用安全限制
        int effectiveChunkSize = Math.min(chunkSize, MAX_SAFE_CHARS);
        
        int step = effectiveChunkSize - chunkOverlap;
        if (step <= 0) step = effectiveChunkSize / 2; // 防止死循环

        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int end = Math.min(start + effectiveChunkSize, text.length());
            // 如果不是最后一块，尝试在句子边界处断开（向前查找，确保不超过限制）
            if (end < text.length()) {
                end = findSafeSentenceBoundary(text, start, end);
            }
            DocumentChunk chunk = new DocumentChunk();
            chunk.setChunkIndex(index++);
            chunk.setContent(text.substring(start, end));
            chunk.setTokenCount(estimateTokens(chunk.getContent()));
            chunk.setSplitStrategy(getStrategy().name());
            chunks.add(chunk);

            if (end >= text.length()) break;

            // 下一块起始位置 = 当前结束位置 - 重叠量
            int nextStart = end - chunkOverlap;
            if (nextStart <= start) nextStart = start + step;
            start = Math.min(nextStart, text.length() - 1);
        }
        return chunks;
    }

    @Override
    public SplitStrategy getStrategy() {
        return SplitStrategy.SLIDING_WINDOW;
    }

    /**
     * 在安全范围内查找句子边界（向前查找，确保不超过 endPos）
     */
    private int findSafeSentenceBoundary(String text, int startPos, int endPos) {
        String candidates = ".!?\n\r";
        // 从 endPos 向前查找，最多查找 50 个字符
        int searchStart = Math.max(startPos, endPos - 50);
        
        for (int i = endPos; i > searchStart; i--) {
            if (candidates.indexOf(text.charAt(i - 1)) >= 0) {
                return i; // 找到句子边界
            }
        }
        
        // 找不到句子边界，强制在 endPos 处断开
        return endPos;
    }
}
