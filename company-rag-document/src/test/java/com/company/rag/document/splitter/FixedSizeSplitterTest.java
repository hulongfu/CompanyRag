package com.company.rag.document.splitter;

import com.company.rag.document.entity.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定大小切分器 FixedSizeSplitter 的单元测试
 */
class FixedSizeSplitterTest {

    private FixedSizeSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new FixedSizeSplitter();
    }

    @Test
    void testSplit_EmptyText_ShouldReturnEmptyList() {
        String emptyText = "";
        int chunkSize = 10;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(emptyText, chunkSize, chunkOverlap);
        assertTrue(chunks.isEmpty());
    }

    @Test
    void testSplit_TextShorterThanChunkSize_ShouldReturnSingleChunk() {
        String text = "Hello";
        int chunkSize = 10;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertEquals(1, chunks.size());
        assertEquals("Hello", chunks.get(0).getContent());
        assertEquals(0, chunks.get(0).getChunkIndex());
    }

    @Test
    void testSplit_TextExactlyChunkSize_ShouldReturnSingleChunk() {
        String text = "1234567890";
        int chunkSize = 10;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertEquals(1, chunks.size());
        assertEquals("1234567890", chunks.get(0).getContent());
    }

    @Test
    void testSplit_TextLongerThanChunkSize_ShouldReturnMultipleChunks() {
        String text = "123456789012345678901234567890";
        int chunkSize = 10;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertEquals(3, chunks.size());
        assertEquals("1234567890", chunks.get(0).getContent());
        assertEquals("1234567890", chunks.get(1).getContent());
        assertEquals("1234567890", chunks.get(2).getContent());
    }

    @Test
    void testSplit_ChunkIndexShouldBeSequential() {
        String text = "123456789012345678901234567890";
        int chunkSize = 10;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertEquals(3, chunks.size());
        assertEquals(0, chunks.get(0).getChunkIndex());
        assertEquals(1, chunks.get(1).getChunkIndex());
        assertEquals(2, chunks.get(2).getChunkIndex());
    }

    @Test
    void testSplit_ChineseText_ShouldSplitCorrectly() {
        // 字符串:"你好世界你好世界你好世界" (12 个字符)
        // 第 1 块:索引 0-5 = "你好世界你好"
        // 第 2 块:索引 6-11 = "世界你好世界"
        String text = "你好世界你好世界你好世界";
        int chunkSize = 6;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertEquals(2, chunks.size());
        assertEquals("你好世界你好", chunks.get(0).getContent());
        assertEquals("世界你好世界", chunks.get(1).getContent());
    }

    @Test
    void testSplit_MixedChineseAndEnglish_ShouldSplitCorrectly() {
        String text = "Hello 你好 World 世界";
        int chunkSize = 10;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertEquals(2, chunks.size());
        assertEquals("Hello 你好 W", chunks.get(0).getContent());
        assertEquals("orld 世界", chunks.get(1).getContent());
    }

    @Test
    void testSplit_TokenCountShouldBeEstimated() {
        String text = "Hello World";
        int chunkSize = 20;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertEquals(1, chunks.size());
        assertNotNull(chunks.get(0).getTokenCount());
        assertTrue(chunks.get(0).getTokenCount() > 0);
    }

    @Test
    void testSplit_TokenCount_ChineseText() {
        String text = "你好世界";
        int chunkSize = 10;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getTokenCount() >= 1);
    }

    @Test
    void testSplit_StrategyShouldBeFixedSIze() {
        SplitStrategy strategy = splitter.getStrategy();
        assertEquals(SplitStrategy.FIXED_SIZE, strategy);
    }

    @Test
    void testSplit_WithOverlapParameter_ShouldIgnoreOverlap() {
        String text = "12345678901234567890";
        int chunkSize = 10;
        int chunkOverlap = 5;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertEquals(2, chunks.size());
        assertEquals("1234567890", chunks.get(0).getContent());
        assertEquals("1234567890", chunks.get(1).getContent());
    }

    @Test
    void testSplit_SpecialCharacters_ShouldPreserveSpecialCharacters() {
        String text = "Hello! @#$%^&*()_+ 世界";
        int chunkSize = 15;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).getContent().contains("!"));
        assertTrue(chunks.get(0).getContent().contains("@"));
    }

    @Test
    void testSplit_NewLineCharacters_ShouldPreserveNewLines() {
        String text = "Line1\nLine2\nLine3";
        int chunkSize = 10;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertTrue(chunks.size() >= 1);
        assertTrue(chunks.get(0).getContent().contains("\n") || 
                   (chunks.size() > 1 && chunks.get(1).getContent().contains("\n")));
    }

    @Test
    void testSplit_WhitespaceOnly_ShouldHandleWhitespace() {
        String text = "   ";
        int chunkSize = 10;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, chunkOverlap);
        assertEquals(1, chunks.size());
        assertEquals("   ", chunks.get(0).getContent());
    }

    @Test
    void testSplit_LargeText_ShouldHandleLargeText() {
        StringBuilder largeText = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            largeText.append("a");
        }
        int chunkSize = 100;
        int chunkOverlap = 0;
        List<DocumentChunk> chunks = splitter.split(largeText.toString(), chunkSize, chunkOverlap);
        assertEquals(10, chunks.size());
        assertEquals(100, chunks.get(0).getContent().length());
    }
}
