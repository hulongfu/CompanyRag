package com.company.rag.rag.tools;

import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.service.RagSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseTool 单元测试
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseToolTest {
    
    @Mock
    private RagSearchService ragSearchService;
    
    @Mock
    private ToolCallRecorder recorder;
    
    @InjectMocks
    private KnowledgeBaseTool knowledgeBaseTool;
    
    @Test
    void shouldReturnErrorWhenQuestionIsEmpty() {
        // Given
        String emptyQuestion = "";
        
        // When
        var result = knowledgeBaseTool.searchKnowledgeBase(emptyQuestion, null);
        
        // Then
        assertFalse(result.isSuccess());
        assertEquals("问题不能为空", result.getError());
    }
    
    @Test
    void shouldReturnErrorWhenNoResultsFound() {
        // Given
        String question = "测试问题";
        RagResult emptyResult = new RagResult();
        emptyResult.setChunks(List.of());
        
        when(ragSearchService.search(any(RagQuery.class))).thenReturn(emptyResult);
        
        // When
        var result = knowledgeBaseTool.searchKnowledgeBase(question, 5);
        
        // Then
        assertFalse(result.isSuccess());
        assertEquals("未找到相关信息", result.getError());
    }
    
    @Test
    void shouldReturnSuccessWithCitations() {
        // Given
        String question = "怎么申请测试环境？";
        
        RagResult.ChunkResult chunk = new RagResult.ChunkResult();
        chunk.setContent("申请测试环境的流程是...");
        chunk.setDocumentName("README.md");
        chunk.setFinalScore(0.95);
        chunk.setChunkIndex(0);
        
        RagResult result = new RagResult();
        result.setChunks(List.of(chunk));
        result.setAnswer("根据文档，申请测试环境需要...");
        
        when(ragSearchService.search(any(RagQuery.class))).thenReturn(result);
        
        // When
        var response = knowledgeBaseTool.searchKnowledgeBase(question, 5);
        
        // Then
        assertTrue(response.isSuccess());
        assertNotNull(response.getAnswer());
        assertEquals(1, response.getCitations().size());
        assertEquals("README.md", response.getCitations().get(0).getFilename());
    }
    
    @Test
    void shouldHandleException() {
        // Given
        String question = "测试问题";
        when(ragSearchService.search(any(RagQuery.class)))
                .thenThrow(new RuntimeException("数据库连接失败"));
        
        // When
        var result = knowledgeBaseTool.searchKnowledgeBase(question, null);
        
        // Then
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("工具调用失败"));
    }
}
