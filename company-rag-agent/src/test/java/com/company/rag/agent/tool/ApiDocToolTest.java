package com.company.rag.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ApiDocTool 测试
 * 验证@Tool 注解是否正确注册并能被调用
 */
class ApiDocToolTest {

    @Test
    void testToolNameAndDescription() {
        // 创建 Mock 依赖
        RequestMappingHandlerMapping mockMapping = mock(RequestMappingHandlerMapping.class);
        when(mockMapping.getHandlerMethods()).thenReturn(new java.util.HashMap<>());
        
        ApiDocTool tool = new ApiDocTool(mockMapping);
        
        assertEquals("api_doc", tool.getName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("API"));
    }

    @Test
    void testExecuteWithoutFilter() {
        RequestMappingHandlerMapping mockMapping = mock(RequestMappingHandlerMapping.class);
        when(mockMapping.getHandlerMethods()).thenReturn(new java.util.HashMap<>());
        
        ApiDocTool tool = new ApiDocTool(mockMapping);
        
        String result = tool.execute(Map.of());
        assertNotNull(result);
        assertTrue(result.contains("API 文档"));
        assertTrue(result.contains("无匹配端点"));
    }

    @Test
    void testExecuteWithFilter() {
        RequestMappingHandlerMapping mockMapping = mock(RequestMappingHandlerMapping.class);
        when(mockMapping.getHandlerMethods()).thenReturn(new java.util.HashMap<>());
        
        ApiDocTool tool = new ApiDocTool(mockMapping);
        
        String result = tool.execute(Map.of("filter", "api"));
        assertNotNull(result);
        assertTrue(result.contains("API 文档"));
    }

    @Test
    void testGenerateApiDocDirectly() {
        RequestMappingHandlerMapping mockMapping = mock(RequestMappingHandlerMapping.class);
        when(mockMapping.getHandlerMethods()).thenReturn(new java.util.HashMap<>());
        
        ApiDocTool tool = new ApiDocTool(mockMapping);
        
        // 直接调用@Tool 注解方法
        String result = tool.generateApiDoc(null);
        assertNotNull(result);
        assertTrue(result.contains("API 文档"));
        assertTrue(result.contains("无匹配端点"));
    }
}
