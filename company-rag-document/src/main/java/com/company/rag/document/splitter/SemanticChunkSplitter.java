package com.company.rag.document.splitter;

import com.company.rag.document.entity.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义边界切分器（RSE风格 - Recursive Semantic Embedding）
 * 按Markdown标题、段落、代码块等语义边界递归切分
 * 亮点：保留文档层级结构，提升检索相关性
 */
@Component
public class SemanticChunkSplitter implements DocumentSplitter {

    // Markdown标题正则
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\\\s+(.+)$", Pattern.MULTILINE);
    // 代码块正则
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```[\\\\s\\\\S]*?```", Pattern.MULTILINE);

    @Override
    public List<DocumentChunk> split(String text, int chunkSize, int chunkOverlap) {
        // 第一阶段：按语义边界粗切（标题、代码块、空行分隔的段落）
        List<String> segments = semanticSegmentation(text);

        // 第二阶段：对过长的段落实行滑动窗口细切
        List<DocumentChunk> chunks = new ArrayList<>();
        int index = 0;
        for (String segment : segments) {
            if (segment.length() <= chunkSize) {
                chunks.add(buildChunk(index++, segment));
            } else {
                // 递归细切
                List<DocumentChunk> subChunks = recursiveSplit(segment, chunkSize, chunkOverlap, index);
                index += subChunks.size();
                chunks.addAll(subChunks);
            }
        }
        return chunks;
    }

    @Override
    public SplitStrategy getStrategy() {
        return SplitStrategy.SEMANTIC_CHUNK;
    }

    private List<String> semanticSegmentation(String text) {
        List<String> segments = new ArrayList<>();
        // 按标题分割
        Matcher matcher = HEADING_PATTERN.matcher(text);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                segments.add(text.substring(lastEnd, matcher.start()).trim());
            }
            lastEnd = matcher.start();
            // 将标题行加入到前一个段落后
            if (!segments.isEmpty()) {
                String last = segments.remove(segments.size() - 1);
                segments.add(last + "\n" + matcher.group());
            } else {
                segments.add(matcher.group());
            }
            lastEnd = matcher.end();
        }
        if (lastEnd < text.length()) {
            segments.add(text.substring(lastEnd).trim());
        }
        // 过滤空段
        segments.removeIf(String::isBlank);
        return segments;
    }

    private List<DocumentChunk> recursiveSplit(String text, int chunkSize, int chunkOverlap, int startIndex) {
        List<DocumentChunk> chunks = new ArrayList<>();
        // 按段落（双换行）切分
        String[] paragraphs = text.split("\n\n");
        StringBuilder current = new StringBuilder();
        int idx = startIndex;

        for (String para : paragraphs) {
            if (current.length() + para.length() > chunkSize && current.length() > 0) {
                chunks.add(buildChunk(idx++, current.toString()));
                // 保留重叠：上一个块的末尾
                int overlapStart = Math.max(0, current.length() - chunkOverlap);
                current = new StringBuilder(current.substring(overlapStart));
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(para);
        }
        if (current.length() > 0) {
            chunks.add(buildChunk(idx, current.toString()));
        }
        return chunks;
    }

    private DocumentChunk buildChunk(int index, String content) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setChunkIndex(index);
        chunk.setContent(content.trim());
        chunk.setTokenCount(estimateTokens(chunk.getContent()));
        chunk.setSplitStrategy(getStrategy().name());
        return chunk;
    }

    private int estimateTokens(String text) {
        int chineseCount = 0;
        int asciiCount = 0;
        for (char c : text.toCharArray()) {
            if (c > 0x4E00 && c < 0x9FFF) chineseCount++;
            else if (c < 128) asciiCount++;
        }
        return (int)(chineseCount / 1.5 + asciiCount / 4.0 + 1);
    }
}
