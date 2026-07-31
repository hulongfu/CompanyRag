package com.company.rag.rag.retriever.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FullTextRetriever 的 buildTsQuery 方法单元测试
 */
@DisplayName("FullTextRetriever.buildTsQuery 测试")
class FullTextRetrieverBuildTsQueryTest {
    
    private FullTextRetriever fullTextRetriever;
    private Method buildTsQueryMethod;
    
    @BeforeEach
    void setUp() throws Exception {
        // 创建测试实例（不需要真实的 JdbcTemplate）
        fullTextRetriever = new FullTextRetriever(null);
        buildTsQueryMethod = FullTextRetriever.class.getDeclaredMethod("buildTsQuery", String.class);
        buildTsQueryMethod.setAccessible(true);
    }
    
    @Test
    @DisplayName("纯中文查询")
    void testChineseQuery() throws Exception {
        String result = (String) buildTsQueryMethod.invoke(fullTextRetriever, "生成文档");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // 应该包含中文字符，不包含特殊符号
        assertTrue(result.contains("生成") || result.contains("文档"));
        assertFalse(result.contains(":*"));
    }
    
    @Test
    @DisplayName("中英文混合查询")
    void testMixedQuery() throws Exception {
        String result = (String) buildTsQueryMethod.invoke(fullTextRetriever, "生成 API 文档");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // 应该包含 API:* 前缀匹配
        assertTrue(result.contains("API:*"), "应该包含 API:* 前缀匹配");
        // 应该包含中文部分
        assertTrue(result.contains("生成") || result.contains("文档"), "应该包含中文部分");
        // 应该用 & 连接
        assertTrue(result.contains(" & "), "应该用 & 连接多个词");
    }
    
    @Test
    @DisplayName("纯英文查询")
    void testEnglishQuery() throws Exception {
        String result = (String) buildTsQueryMethod.invoke(fullTextRetriever, "API documentation");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // 应该包含前缀匹配
        assertTrue(result.contains("API:*"), "应该包含 API:*");
        assertTrue(result.contains("documentation:*"), "应该包含 documentation:*");
    }
    
    @Test
    @DisplayName("单个英文单词查询")
    void testSingleEnglishWord() throws Exception {
        String result = (String) buildTsQueryMethod.invoke(fullTextRetriever, "API");
        assertNotNull(result);
        assertEquals("API:*", result);
    }
    
    @Test
    @DisplayName("空查询")
    void testEmptyQuery() throws Exception {
        String result = (String) buildTsQueryMethod.invoke(fullTextRetriever, "");
        assertNotNull(result);
        assertEquals("", result);
    }
    
    @Test
    @DisplayName("null 查询")
    void testNullQuery() throws Exception {
        String result = (String) buildTsQueryMethod.invoke(fullTextRetriever, null);
        assertNotNull(result);
        assertEquals("", result);
    }
    
    @Test
    @DisplayName("包含特殊字符的查询")
    void testSpecialCharacters() throws Exception {
        String result = (String) buildTsQueryMethod.invoke(fullTextRetriever, "API & 文档");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // 特殊字符应该被转义
        assertFalse(result.contains("&&"), "不应该包含连续的 &");
    }
    
    @Test
    @DisplayName("数字和字母混合")
    void testAlphanumeric() throws Exception {
        String result = (String) buildTsQueryMethod.invoke(fullTextRetriever, "API3.0 接口");
        assertNotNull(result);
        assertFalse(result.isEmpty());
        // 应该提取 API3 和 0 作为英文词
        assertTrue(result.contains("API3:*") || result.contains("0:*"));
    }
}
