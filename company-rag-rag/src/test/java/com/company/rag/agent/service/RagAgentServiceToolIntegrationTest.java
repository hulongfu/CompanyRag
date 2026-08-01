package com.company.rag.rag.tools;

import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.rag.config.AgentToolConfig;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.service.RagSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * KnowledgeBaseTool 集成测试
 * 验证工具是否正确注册到 ToolCallbackProvider
 */
@SpringBootTest(classes = {
        AgentToolConfig.class,
        KnowledgeBaseTool.class
})
class KnowledgeBaseToolIntegrationTest {
    
    @MockBean
    private RagSearchService ragSearchService;
    
    @MockBean
    private ToolCallRecorder recorder;
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private ToolCallbackProvider toolCallbackProvider;
    
    @Test
    void shouldRegisterKnowledgeBaseTool() {
        // Given & When
        var tools = applicationContext.getBeansOfType(KnowledgeBaseTool.class);
        
        // Then
        assertTrue(tools.containsKey("knowledgeBaseTool"), "应该注册 knowledgeBaseTool");
    }
    
    @Test
    void shouldProvideToolCallbacks() {
        // Given & When
        var callbacks = toolCallbackProvider.getToolCallbacks();
        
        // Then
        assertNotNull(callbacks, "ToolCallbackProvider 应该提供工具回调");
        assertTrue(callbacks.length >= 1, "应该至少有 1 个工具回调");
    }
    
    @Test
    void shouldHaveSearchKnowledgeBaseTool() {
        // Given & When
        var callbacks = toolCallbackProvider.getToolCallbacks();
        
        // Then
        boolean hasKnowledgeBaseTool = false;
        String knowledgeBaseDescription = null;
        for (var callback : callbacks) {
            if (callback.getToolDefinition().name().equals("searchKnowledgeBase")) {
                hasKnowledgeBaseTool = true;
                knowledgeBaseDescription = callback.getToolDefinition().description();
                break;
            }
        }
        
        assertTrue(hasKnowledgeBaseTool, "应该注册 searchKnowledgeBase 工具");
        assertNotNull(knowledgeBaseDescription, "工具描述不应该为空");
        assertTrue(knowledgeBaseDescription.contains("知识库"), "工具描述应该包含'知识库'");
    }
    
    @Test
    void shouldReturnSuccessWhenRagSearchWorks() {
        // Given
        String question = "怎么申请测试环境？";
        RagResult mockResult = new RagResult();
        RagResult.ChunkResult chunk = new RagResult.ChunkResult();
        chunk.setContent("申请测试环境的流程...");
        chunk.setDocumentName("README.md");
        chunk.setFinalScore(0.95);
        chunk.setChunkIndex(0);
        mockResult.setChunks(List.of(chunk));
        mockResult.setAnswer("根据文档，申请测试环境需要...");
        
        when(ragSearchService.search(any(RagQuery.class))).thenReturn(mockResult);
        
        // When
        KnowledgeBaseTool tool = applicationContext.getBean(KnowledgeBaseTool.class);
        var result = tool.searchKnowledgeBase(question, 5);
        
        // Then
        assertTrue(result.isSuccess(), "应该返回成功");
        assertNotNull(result.getAnswer(), "答案不应该为空");
        assertEquals(1, result.getCitations().size(), "应该有 1 个引用");
    }
}
