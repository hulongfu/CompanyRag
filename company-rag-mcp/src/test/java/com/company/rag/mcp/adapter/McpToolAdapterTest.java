package com.company.rag.mcp.adapter;

import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.model.McpToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * McpToolAdapter 单元测试
 */
@ExtendWith(MockitoExtension.class)
class McpToolAdapterTest {
    
    @Mock
    private AgentToolRegistry agentToolRegistry;
    
    private McpToolAdapter adapter;
    
    @BeforeEach
    void setUp() {
        adapter = new McpToolAdapter(agentToolRegistry);
    }
    
    @Test
    @DisplayName("列出所有可用工具")
    void listTools() {
        // 准备测试数据
        List<Map<String, Object>> mockTools = List.of(
            Map.of(
                "name", "database_query",
                "description", "数据库查询工具",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "sql", Map.of("type", "string", "description", "SQL 语句")
                    )
                )
            )
        );
        
        when(agentToolRegistry.listTools()).thenReturn(mockTools);
        
        // 执行测试
        List<McpToolDefinition> tools = adapter.listTools();
        
        // 验证结果
        assertEquals(1, tools.size());
        McpToolDefinition toolDef = tools.get(0);
        assertEquals("database_query", toolDef.getName());
        assertEquals("数据库查询工具", toolDef.getDescription());
        assertNotNull(toolDef.getInputSchema());
    }
    
    @Test
    @DisplayName("调用工具成功")
    void callToolSuccess() {
        String toolName = "database_query";
        Map<String, Object> args = Map.of("sql", "SELECT * FROM users");
        String expectedResult = "查询结果：...";
        
        when(agentToolRegistry.executeTool(toolName, args))
                .thenReturn(expectedResult);
        
        String result = adapter.callTool(toolName, args);
        
        assertEquals(expectedResult, result);
        verify(agentToolRegistry).executeTool(toolName, args);
    }
    
    @Test
    @DisplayName("调用工具失败")
    void callToolFailure() {
        String toolName = "database_query";
        Map<String, Object> args = Map.of("sql", "INVALID SQL");
        
        when(agentToolRegistry.executeTool(toolName, args))
                .thenThrow(new RuntimeException("SQL 语法错误"));
        
        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> adapter.callTool(toolName, args)
        );
        
        assertTrue(exception.getMessage().contains("工具调用失败"));
        verify(agentToolRegistry).executeTool(toolName, args);
    }
}
