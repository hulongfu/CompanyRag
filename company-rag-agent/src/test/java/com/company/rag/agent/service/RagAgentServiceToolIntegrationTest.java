package com.company.rag.agent.service;

import com.company.rag.agent.config.AgentToolConfig;
import com.company.rag.agent.tool.ApiDocTool;
import com.company.rag.agent.tool.CodeSearchTool;
import com.company.rag.agent.tool.DatabaseQueryTool;
import com.company.rag.rag.tools.KnowledgeBaseTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RagAgentService 工具集成测试
 * 验证所有工具是否正确注册到 ToolCallbackProvider
 */
@SpringBootTest(classes = {
        AgentToolConfig.class,
        DatabaseQueryTool.class,
        ApiDocTool.class,
        CodeSearchTool.class,
        KnowledgeBaseTool.class
})
class RagAgentServiceToolIntegrationTest {
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private ToolCallbackProvider toolCallbackProvider;
    
    @Test
    void shouldRegisterAllTools() {
        // Given & When
        var tools = applicationContext.getBeansOfType(Object.class);
        
        // Then
        assertTrue(tools.containsKey("databaseQueryTool"), "应该注册 databaseQueryTool");
        assertTrue(tools.containsKey("apiDocTool"), "应该注册 apiDocTool");
        assertTrue(tools.containsKey("codeSearchTool"), "应该注册 codeSearchTool");
        assertTrue(tools.containsKey("knowledgeBaseTool"), "应该注册 knowledgeBaseTool");
    }
    
    @Test
    void shouldProvideToolCallbacks() {
        // Given & When
        var callbacks = toolCallbackProvider.getToolCallbacks();
        
        // Then
        assertNotNull(callbacks, "ToolCallbackProvider 不应为 null");
        assertEquals(4, callbacks.length, "应该有 4 个工具回调");
    }
    
    @Test
    void shouldHaveSearchKnowledgeBaseTool() {
        // Given & When
        var callbacks = toolCallbackProvider.getToolCallbacks();
        
        // Then
        boolean hasKnowledgeBaseTool = false;
        for (var callback : callbacks) {
            if (callback.getToolDefinition().name().equals("searchKnowledgeBase")) {
                hasKnowledgeBaseTool = true;
                break;
            }
        }
        assertTrue(hasKnowledgeBaseTool, "应该注册 searchKnowledgeBase 工具");
    }
}
