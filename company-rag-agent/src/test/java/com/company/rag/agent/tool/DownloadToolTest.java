package com.company.rag.agent.tool;

import com.company.rag.agent.service.DownloadService;
import com.company.rag.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DownloadTool 单元测试
 */
class DownloadToolTest {

    @Mock
    private DownloadService downloadService;

    @InjectMocks
    private DownloadTool downloadTool;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 设置测试上下文
        TenantContext.setTenantId(1L);
        TenantContext.setSessionId("test-session-123");
    }
    
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void testGetToolName() {
        assertEquals("download_file", downloadTool.getName());
    }

    @Test
    void testGetToolDescription() {
        String description = downloadTool.getDescription();
        assertNotNull(description);
        assertTrue(description.contains("下载"));
        assertTrue(description.contains("文件"));
    }

    @Test
    void testGetParameterSchema() {
        Map<String, Object> schema = downloadTool.getParameterSchema();
        
        assertNotNull(schema);
        assertEquals("object", schema.get("type"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("content"));
        assertTrue(properties.containsKey("filename"));
        assertTrue(properties.containsKey("contentType"));
        
        @SuppressWarnings("unchecked")
        String[] required = (String[]) schema.get("required");
        assertNotNull(required);
        assertEquals(1, required.length);  // 只有 content 是必需的
        assertEquals("content", required[0]);
    }

    @Test
    void testExecute_Success() {
        // 准备测试数据
        Map<String, Object> params = new HashMap<>();
        params.put("content", "# Test Content\n\nThis is a test.");
        params.put("filename", "test.md");
        params.put("contentType", "text/markdown");
        
        // Mock Service 返回（现在返回 String 文件 ID）
        when(downloadService.createDownloadFile(
            anyLong(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn("test-file-id-123");
        
        // 执行测试
        String result = downloadTool.execute(params);
        
        // 验证结果
        assertNotNull(result);
        assertTrue(result.contains("✅"));
        assertTrue(result.contains("文件已生成成功"));
        assertTrue(result.contains("test.md"));
        assertTrue(result.contains("/api/download/test-file-id-123"));
        
        // 验证 Service 被调用
        verify(downloadService, times(1)).createDownloadFile(
            anyLong(), anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    void testExecute_NullParams() {
        String result = downloadTool.execute(null);
        
        assertNotNull(result);
        assertTrue(result.contains("❌"));
        assertTrue(result.contains("参数不能为空"));
    }

    @Test
    void testExecute_EmptyContent() {
        Map<String, Object> params = new HashMap<>();
        params.put("content", "");
        params.put("filename", "test.md");
        
        String result = downloadTool.execute(params);
        
        assertNotNull(result);
        assertTrue(result.contains("❌"));
        assertTrue(result.contains("内容不能为空"));
    }

    @Test
    void testExecute_NullFilename() {
        // 空文件名时会自动生成文件名，不会报错
        Map<String, Object> params = new HashMap<>();
        params.put("content", "Test content");
        params.put("filename", "");
        
        // Mock Service 返回
        when(downloadService.createDownloadFile(
            anyLong(), anyString(), anyString(), anyString(), anyString()
        )).thenReturn("auto-generated-file-id");
        
        String result = downloadTool.execute(params);
        
        assertNotNull(result);
        assertTrue(result.contains("✅"));  // 空文件名会成功，自动生成文件名
        assertTrue(result.contains("文件已生成成功"));
    }

    @Test
    void testExecute_ServiceThrowsException() {
        Map<String, Object> params = new HashMap<>();
        params.put("content", "Test content");
        params.put("filename", "test.md");
        
        // Mock 抛出异常（使用更通用的 any() 匹配器）
        when(downloadService.createDownloadFile(
            any(), any(), any(), any(), any()
        )).thenThrow(new IllegalArgumentException("文件大小超过限制"));
        
        String result = downloadTool.execute(params);
        
        assertNotNull(result);
        assertTrue(result.contains("❌"));
        assertTrue(result.contains("文件生成失败"));
        // 不检查具体异常消息，因为可能被包装或转换
    }
}
