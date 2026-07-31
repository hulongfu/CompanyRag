package com.company.rag.rag.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseResultTest {
    
    @Test
    void shouldCreateSuccessResult() {
        // Given
        String answer = "这是答案";
        List<KnowledgeBaseResult.Citation> citations = List.of(
            new KnowledgeBaseResult.Citation("README.md", "内容预览", 0.95, 0)
        );
        
        // When
        KnowledgeBaseResult result = KnowledgeBaseResult.ok(answer, citations);
        
        // Then
        assertTrue(result.isSuccess());
        assertEquals("这是答案", result.getAnswer());
        assertEquals(1, result.getCitations().size());
        assertNull(result.getError());
    }
    
    @Test
    void shouldCreateFailedResult() {
        // Given
        String error = "检索失败";
        
        // When
        KnowledgeBaseResult result = KnowledgeBaseResult.failed(error);
        
        // Then
        assertFalse(result.isSuccess());
        assertEquals("检索失败", result.getError());
        assertNull(result.getAnswer());
        assertTrue(result.getCitations().isEmpty());
    }
}
