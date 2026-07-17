package com.company.rag.document.splitter;

import com.company.rag.document.entity.DocumentChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语义切分器测试 - 验证 token 超限修复
 */
class SemanticChunkSplitterTest {

    private SemanticChunkSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new SemanticChunkSplitter();
    }

    @Test
    void testNormalText() {
        // 正常文本测试
        String text = "这是第一段。\n\n这是第二段。\n\n这是第三段。";
        List<DocumentChunk> chunks = splitter.split(text, 512, 64);
        
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 1);
    }

    @Test
    void testLongParagraph_exceedsTokenLimit() {
        // 构造超长段落（超过 400 字符）
        StringBuilder longParagraph = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            longParagraph.append("这是一个测试句子，用于验证超长段落的切分功能。");
        }
        
        String text = longParagraph.toString();
        List<DocumentChunk> chunks = splitter.split(text, 512, 64);
        
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        
        // 验证每个 chunk 都不超过最大字符数限制
        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.getContent().length() <= 400, 
                "Chunk 长度超过限制：" + chunk.getContent().length());
            assertNotNull(chunk.getTokenCount());
        }
        
        System.out.println("超长段落测试通过 - 共切分为 " + chunks.size() + " 个块");
    }

    @Test
    void testMixedContent() {
        // 混合内容：正常段落 + 超长段落
        StringBuilder text = new StringBuilder();
        text.append("这是正常的短段落。\n\n");
        
        // 添加超长段落
        for (int i = 0; i < 300; i++) {
            text.append("这是一个很长的句子，");
        }
        text.append("\n\n");
        text.append("这是另一个正常段落。");
        
        List<DocumentChunk> chunks = splitter.split(text.toString(), 512, 64);
        
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        
        // 验证每个 chunk 都不超过限制
        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.getContent().length() <= 400, 
                "Chunk 长度超过限制：" + chunk.getContent().length());
        }
    }

    @Test
    void testCodeBlock() {
        // 代码块测试
        StringBuilder codeBlock = new StringBuilder("```\n");
        for (int i = 0; i < 100; i++) {
            codeBlock.append("public class TestClass").append(i).append(" {\n");
            codeBlock.append("    public void method").append(i).append("() {\n");
            codeBlock.append("        System.out.println(\"Hello World\");\n");
            codeBlock.append("    }\n");
            codeBlock.append("}\n");
        }
        codeBlock.append("```");
        
        List<DocumentChunk> chunks = splitter.split(codeBlock.toString(), 512, 64);
        
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        
        // 验证每个 chunk 都不超过限制
        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.getContent().length() <= 400, 
                "Chunk 长度超过限制：" + chunk.getContent().length());
        }
        
        System.out.println("代码块测试通过 - 共切分为 " + chunks.size() + " 个块");
    }

    @Test
    void testMarkdownHeadings() {
        // Markdown 标题测试
        String text = "# 一级标题\n\n" +
                     "## 二级标题\n\n" +
                     "### 三级标题\n\n" +
                     "这是段落内容。\n\n" +
                     "#### 四级标题\n\n" +
                     "更多内容...";
        
        List<DocumentChunk> chunks = splitter.split(text, 512, 64);
        
        assertNotNull(chunks);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 1);
    }

    @Test
    void testTokenEstimation() {
        // 验证 token 估算不会为负数或零
        String text = "这是一个测试文本，用于验证 token 估算功能。";
        List<DocumentChunk> chunks = splitter.split(text, 512, 64);
        
        for (DocumentChunk chunk : chunks) {
            assertTrue(chunk.getTokenCount() > 0, "Token 数应该大于 0");
        }
    }
}
