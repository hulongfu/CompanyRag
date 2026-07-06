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
        List<DocumentChunk> chunks = new ArrayList<>();
        int step = chunkSize - chunkOverlap;
        if (step <= 0) step = chunkSize / 2; // 防止死循环

        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            // 如果不是最后一块，尝试在边界处断句
            if (end < text.length()) {
                end = findSentenceBoundary(text, end);
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
     * 在句子边界处断开（句号、问号、感叹号、换行等）
     */
    private int findSentenceBoundary(String text, int fromPos) {
        String candidates = ".。!！?？\n\r";
        for (int i = fromPos; i < Math.min(fromPos + 50, text.length()); i++) {
            if (candidates.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return fromPos;
    }
}
