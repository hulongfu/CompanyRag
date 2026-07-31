package com.company.rag.rag.tools;

import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.rag.model.KnowledgeBaseResult;
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
import static org.mockito.Mockito.*;

/**
 * KnowledgeBaseTool 端到端测试
 * 验证工具能否正确处理各种场景
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseToolEndToEndTest {
    
    @Mock
    private RagSearchService ragSearchService;
    
    @Mock
    private ToolCallRecorder recorder;
    
    @InjectMocks
    private KnowledgeBaseTool knowledgeBaseTool;
    
    @Test
    void shouldCallKnowledgeBaseToolWithValidQuestion() {
        // Given: 用户问"怎么申请测试环境？"
        String question = "怎么申请测试环境？";
        Integer topK = 5;
        
        RagResult.ChunkResult chunk = new RagResult.ChunkResult();
        chunk.setContent("申请测试环境需要提交工单...");
        chunk.setDocumentName("测试环境管理规范.md");
        chunk.setFinalScore(0.92);
        chunk.setChunkIndex(0);
        
        RagResult ragResult = new RagResult();
        ragResult.setChunks(List.of(chunk));
        ragResult.setAnswer("根据文档，申请测试环境需要提交工单审批。");
        
        when(ragSearchService.search(any(RagQuery.class))).thenReturn(ragResult);
        
        // When: 调用工具
        KnowledgeBaseResult result = knowledgeBaseTool.searchKnowledgeBase(question, topK);
        
        // Then: 验证返回结果正确
        assertTrue(result.isSuccess(), "应该成功返回结果");
        assertNotNull(result.getAnswer(), "答案不应为空");
        assertEquals(1, result.getCitations().size(), "应该有 1 个引用");
        assertEquals("测试环境管理规范.md", result.getCitations().get(0).getFilename());
        
        // 验证调用了 RAG 服务
        verify(ragSearchService, times(1)).search(any(RagQuery.class));
        verify(recorder, times(1)).recordStart("searchKnowledgeBase", any());
        verify(recorder, times(1)).recordEnd("searchKnowledgeBase", "success");
    }
    
    @Test
    void shouldHandleEmptyQuestion() {
        // Given: 空问题
        String emptyQuestion = "";
        
        // When: 调用工具
        KnowledgeBaseResult result = knowledgeBaseTool.searchKnowledgeBase(emptyQuestion, null);
        
        // Then: 应该返回错误
        assertFalse(result.isSuccess());
        assertEquals("问题不能为空", result.getError());
        verify(recorder, times(1)).recordEnd("searchKnowledgeBase", "failed");
    }
    
    @Test
    void shouldHandleNoResultsFound() {
        // Given: 检索结果为空
        String question = "一个不存在的问题";
        RagResult emptyResult = new RagResult();
        emptyResult.setChunks(List.of());
        
        when(ragSearchService.search(any(RagQuery.class))).thenReturn(emptyResult);
        
        // When: 调用工具
        KnowledgeBaseResult result = knowledgeBaseTool.searchKnowledgeBase(question, 5);
        
        // Then: 应该返回"未找到相关信息"
        assertFalse(result.isSuccess());
        assertEquals("未找到相关信息", result.getError());
    }
    
    @Test
    void shouldHandleExceptionGracefully() {
        // Given: RAG 服务抛出异常
        String question = "测试问题";
        when(ragSearchService.search(any(RagQuery.class)))
                .thenThrow(new RuntimeException("数据库连接失败"));
        
        // When: 调用工具
        KnowledgeBaseResult result = knowledgeBaseTool.searchKnowledgeBase(question, null);
        
        // Then: 应该捕获异常并返回友好错误
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("工具调用失败"));
        verify(recorder, times(1)).recordEnd("searchKnowledgeBase", "failed");
    }
    
    @Test
    void shouldUseDefaultTopKWhenNotProvided() {
        // Given: 不指定 topK
        String question = "测试问题";
        
        RagResult.ChunkResult chunk = new RagResult.ChunkResult();
        chunk.setContent("测试内容");
        chunk.setDocumentName("test.md");
        chunk.setFinalScore(0.85);
        chunk.setChunkIndex(0);
        
        RagResult ragResult = new RagResult();
        ragResult.setChunks(List.of(chunk));
        
        when(ragSearchService.search(any(RagQuery.class))).thenReturn(ragResult);
        
        // When: 调用工具，topK 为 null
        KnowledgeBaseResult result = knowledgeBaseTool.searchKnowledgeBase(question, null);
        
        // Then: 应该使用默认 topK=5
        assertTrue(result.isSuccess());
        verify(ragSearchService).search(argThat(query -> query.getTopK() == 5));
    }
}
