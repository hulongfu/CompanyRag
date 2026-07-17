package com.company.rag.document.splitter;

import com.company.rag.document.entity.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 token 超限修复的集成测试
 */
class TokenLimitIntegrationTest {

    @Test
    void testAllSplittersWithLongText() {
        // 构造一个超长的 TXT 文件内容（模拟真实场景）
        StringBuilder longText = new StringBuilder();
        
        // 添加标题
        longText.append("# 公司知识库文档\n\n");
        
        // 添加多个超长段落
        for (int section = 1; section <= 5; section++) {
            longText.append("## 第").append(section).append("章节\n\n");
            
            // 超长段落（每段约 2000 字符）
            for (int i = 0; i < 100; i++) {
                longText.append("这是一个非常重要的知识点，需要详细描述。");
            }
            longText.append("\n\n");
            
            // 代码块
            longText.append("```\n");
            for (int i = 0; i < 50; i++) {
                longText.append("public class Example").append(i).append(" {\n");
                longText.append("    public void doSomething() {\n");
                longText.append("        System.out.println(\"Hello\");\n");
                longText.append("    }\n");
                longText.append("}\n");
            }
            longText.append("```\n\n");
        }
        
        String text = longText.toString();
        System.out.println("测试文本总长度：" + text.length() + " 字符");
        
        // 测试所有切分器
        testSplitter(new SemanticChunkSplitter(), text, "语义切分器");
        testSplitter(new FixedSizeSplitter(), text, "固定大小切分器");
        testSplitter(new SlidingWindowSplitter(), text, "滑动窗口切分器");
    }
    
    private void testSplitter(DocumentSplitter splitter, String text, String name) {
        System.out.println("\n=== 测试 " + name + " ===");
        
        List<DocumentChunk> chunks = splitter.split(text, 512, 64);
        
        assertNotNull(chunks, name + " 返回 null");
        assertFalse(chunks.isEmpty(), name + " 返回空列表");
        
        System.out.println(name + " 切分结果：共 " + chunks.size() + " 个块");
        
        // 验证每个 chunk 都不超过安全限制
        int maxChunkSize = 0;
        int maxTokenCount = 0;
        
        for (DocumentChunk chunk : chunks) {
            int length = chunk.getContent().length();
            maxChunkSize = Math.max(maxChunkSize, length);
            maxTokenCount = Math.max(maxTokenCount, chunk.getTokenCount());
            
            // 关键验证：确保不超过 400 字符（安全阈值）
            assertTrue(length <= 400, 
                name + " 产生超限 chunk: " + length + " 字符，内容：" + 
                chunk.getContent().substring(0, Math.min(50, chunk.getContent().length())) + "...");
            
            // 验证 token 估算合理（400 字符应该 < 512 tokens）
            assertTrue(chunk.getTokenCount() > 0, 
                name + " 的 token 数应该 > 0");
        }
        
        System.out.println(name + " 最大 chunk 长度：" + maxChunkSize + " 字符");
        System.out.println(name + " 最大 token 数：" + maxTokenCount);
        System.out.println(name + " ✓ 通过验证");
    }
}
