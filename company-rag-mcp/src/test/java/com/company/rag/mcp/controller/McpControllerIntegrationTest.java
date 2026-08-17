package com.company.rag.mcp.controller;

import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.adapter.McpToolAdapter;
import com.company.rag.mcp.handler.JsonRpcHandler;
import com.company.rag.mcp.model.McpToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * McpController 端到端集成测试
 * 
 * 测试场景：
 * 1. GET /mcp/tools - 列出所有可用工具
 * 2. POST /mcp - tools/list 方法
 * 3. POST /mcp - tools/call 方法（成功）
 * 4. POST /mcp - tools/call 方法（失败）
 * 5. POST /mcp - 不支持的方法
 * 6. POST /mcp - 无效的 JSON-RPC 请求
 */
@WebMvcTest(McpController.class)
@ContextConfiguration(classes = McpControllerIntegrationTest.TestConfig.class)
class McpControllerIntegrationTest {
    
    /**
     * 测试配置类
     */
    @Configuration
    @ComponentScan(basePackages = "com.company.rag.mcp", 
                   excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.company\\.rag\\.mcp\\.filter\\..*"))
    static class TestConfig {
        
        // 禁用 Spring Security 自动配置（用于测试）
        @org.springframework.boot.test.context.TestConfiguration
        static class SecurityDisableConfig {
            @org.springframework.context.annotation.Bean
            org.springframework.security.web.SecurityFilterChain testFilterChain(
                    org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
                http
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
                return http.build();
            }
        }
    }
    
    @Autowired
    private MockMvc mockMvc;
    
    // 只 Mock AgentToolRegistry，其他 Bean 使用真实实现
    @MockBean
    private AgentToolRegistry agentToolRegistry;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Test
    void testGetTools() throws Exception {
        // 准备测试数据
        when(agentToolRegistry.listTools()).thenReturn(
            List.of(Map.of(
                "name", "database_query",
                "description", "数据库查询工具",
                "parameters", Map.of("type", "object")
            ))
        );
        
        // 执行测试
        mockMvc.perform(get("/mcp/tools")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("database_query"))
                .andExpect(jsonPath("$[0].description").value("数据库查询工具"));
    }
    
    @Test
    void testPostToolsList() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/list",
                "id": 1
            }
            """;
        
        // 准备测试数据
        when(agentToolRegistry.listTools()).thenReturn(
            List.of(Map.of(
                "name", "database_query",
                "description", "数据库查询工具",
                "parameters", Map.of("type", "object")
            ))
        );
        
        // 执行测试
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.tools[0].name").value("database_query"));
    }
    
    @Test
    void testPostToolsCall() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/call",
                "id": 2,
                "params": {
                    "name": "database_query",
                    "arguments": {
                        "sql": "SELECT * FROM users"
                    }
                }
            }
            """;
        
        // 准备测试数据
        when(agentToolRegistry.executeTool(eq("database_query"), anyMap()))
                .thenReturn("查询结果：10 条记录");
        
        // 执行测试
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value("查询结果：10 条记录"));
    }
    
    @Test
    void testPostToolsCallFailure() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/call",
                "id": 3,
                "params": {
                    "name": "database_query",
                    "arguments": {
                        "sql": "INVALID SQL"
                    }
                }
            }
            """;
        
        // 准备测试数据
        when(agentToolRegistry.executeTool(eq("database_query"), anyMap()))
                .thenThrow(new RuntimeException("SQL 语法错误"));
        
        // 执行测试
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error.message").value("工具调用失败：SQL 语法错误"));
    }
    
    @Test
    void testUnsupportedMethod() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "unsupported/method",
                "id": 4
            }
            """;
        
        // 执行测试
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error.message").value("不支持的方法：unsupported/method"));
    }
    
    @Test
    void testInvalidRequest() throws Exception {
        String requestBody = """
            {
                "invalid": "json-rpc"
            }
            """;
        
        // 执行测试
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isBadRequest());
    }
}
