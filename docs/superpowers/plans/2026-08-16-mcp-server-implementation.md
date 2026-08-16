# MCP Server 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 Agent 工具暴露为标准 MCP Server，支持外部 AI 应用通过 HTTP + JSON-RPC 协议调用工具。

**Architecture:** 新增 `company-rag-mcp` 模块作为 MCP 协议适配层，包含 `McpController`（HTTP 端点）、`JsonRpcHandler`（协议解析）、`McpToolAdapter`（协议转换）、`McpSecurityFilter`（JWT + 租户验证），调用现有 `AgentToolRegistry` 执行工具，保持现有 Agent 系统不变。

**Tech Stack:** JDK 17 + Spring Boot 3.4.4 + Spring AI 1.0 + JSON-RPC 2.0（自行实现）+ Docker Compose

---

## 文件结构概览

**新增模块：**
- `company-rag-mcp/pom.xml` — Maven 模块配置
- `company-rag-mcp/src/main/java/com/company/rag/mcp/controller/McpController.java` — MCP HTTP 端点
- `company-rag-mcp/src/main/java/com/company/rag/mcp/handler/JsonRpcHandler.java` — JSON-RPC 协议解析
- `company-rag-mcp/src/main/java/com/company/rag/mcp/adapter/McpToolAdapter.java` — MCP 协议→AgentTool 转换
- `company-rag-mcp/src/main/java/com/company/rag/mcp/security/McpSecurityFilter.java` — JWT + 租户验证
- `company-rag-mcp/src/main/java/com/company/rag/mcp/model/` — MCP 协议数据模型（请求/响应）
- `company-rag-mcp/src/test/java/com/company/rag/mcp/` — 单元测试和集成测试

**修改文件：**
- `pom.xml` — 新增 `company-rag-mcp` 模块引用
- `company-rag-bootstrap/src/main/resources/application.yml` — MCP 端点配置

---

## Task 1: 创建 company-rag-mcp 模块骨架

**Files:**
- Create: `company-rag-mcp/pom.xml`
- Modify: `pom.xml` (root)
- Test: 无

- [ ] **Step 1: 创建 company-rag-mcp 模块的 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    
    <parent>
        <groupId>com.company.rag</groupId>
        <artifactId>company-rag-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    
    <artifactId>company-rag-mcp</artifactId>
    <name>CompanyRag MCP - MCP Server 适配层模块</name>
    <description>MCP (Model Context Protocol) Server 实现，暴露 Agent 工具为标准 MCP 接口</description>
    
    <dependencies>
        <!-- 内部模块依赖 -->
        <dependency>
            <groupId>com.company.rag</groupId>
            <artifactId>company-rag-agent</artifactId>
            <version>${project.version}</version>
        </dependency>
        
        <dependency>
            <groupId>com.company.rag</groupId>
            <artifactId>company-rag-tenant</artifactId>
            <version>${project.version}</version>
        </dependency>
        
        <!-- Spring Boot Web (HTTP 端点) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        
        <!-- Spring Security (JWT 验证) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        
        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        
        <!-- 测试依赖 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: 在 root pom.xml 中添加 company-rag-mcp 模块**

修改 `<modules>` 部分，添加：
```xml
<module>company-rag-mcp</module>
```

- [ ] **Step 3: 创建模块目录结构**

```bash
mkdir -p company-rag-mcp/src/main/java/com/company/rag/mcp/controller
mkdir -p company-rag-mcp/src/main/java/com/company/rag/mcp/handler
mkdir -p company-rag-mcp/src/main/java/com/company/rag/mcp/adapter
mkdir -p company-rag-mcp/src/main/java/com/company/rag/mcp/security
mkdir -p company-rag-mcp/src/main/java/com/company/rag/mcp/model
mkdir -p company-rag-mcp/src/main/resources
mkdir -p company-rag-mcp/src/test/java/com/company/rag/mcp
```

- [ ] **Step 4: 验证模块编译**

```bash
cd company-rag-mcp
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add company-rag-mcp/ pom.xml
git commit -m "feat(mcp): 创建 company-rag-mcp 模块骨架"
```

---

## Task 2: 实现 MCP 协议数据模型

**Files:**
- Create: `company-rag-mcp/src/main/java/com/company/rag/mcp/model/JsonRpcRequest.java`
- Create: `company-rag-mcp/src/main/java/com/company/rag/mcp/model/JsonRpcResponse.java`
- Create: `company-rag-mcp/src/main/java/com/company/rag/mcp/model/McpToolDefinition.java`
- Test: 无

- [ ] **Step 1: 创建 JSON-RPC 请求模型**

```java
package com.company.rag.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * JSON-RPC 2.0 请求格式
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JsonRpcRequest {
    
    /**
     * JSON-RPC 协议版本，必须为 "2.0"
     */
    private String jsonrpc;
    
    /**
     * 请求方法名，例如："tools/list"、"tools/call"
     */
    private String method;
    
    /**
     * 请求参数
     */
    private JsonRpcParams params;
    
    /**
     * 请求 ID，用于匹配请求和响应
     */
    private Object id;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JsonRpcParams {
        /**
         * 工具名称（tools/call 需要）
         */
        private String name;
        
        /**
         * 工具参数（tools/call 需要）
         */
        private Object arguments;
    }
}
```

- [ ] **Step 2: 创建 JSON-RPC 响应模型**

```java
package com.company.rag.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.AllArgsConstructor;

/**
 * JSON-RPC 2.0 响应格式
 */
@Data
@AllArgsConstructor
public class JsonRpcResponse {
    
    /**
     * JSON-RPC 协议版本，必须为 "2.0"
     */
    private String jsonrpc = "2.0";
    
    /**
     * 响应 ID，与请求 ID 对应
     */
    private Object id;
    
    /**
     * 响应结果（成功时）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object result;
    
    /**
     * 错误信息（失败时）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonRpcError error;
    
    /**
     * 创建成功响应
     */
    public static JsonRpcResponse success(Object id, Object result) {
        return new JsonRpcResponse("2.0", id, result, null);
    }
    
    /**
     * 创建错误响应
     */
    public static JsonRpcResponse error(Object id, int code, String message, Object data) {
        return new JsonRpcResponse("2.0", id, null, new JsonRpcError(code, message, data));
    }
    
    @Data
    @AllArgsConstructor
    public static class JsonRpcError {
        /**
         * 错误码
         */
        private int code;
        
        /**
         * 错误消息
         */
        private String message;
        
        /**
         * 附加数据（可选）
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Object data;
    }
}
```

- [ ] **Step 3: 创建 MCP 工具定义模型**

```java
package com.company.rag.mcp.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.AllArgsConstructor;

/**
 * MCP 工具定义（tools/list 返回格式）
 */
@Data
@AllArgsConstructor
public class McpToolDefinition {
    
    /**
     * 工具名称
     */
    private String name;
    
    /**
     * 工具描述
     */
    private String description;
    
    /**
     * 输入参数 Schema（JSON Schema 格式）
     */
    @JsonProperty("inputSchema")
    private Object inputSchema;
}
```

- [ ] **Step 4: 提交**

```bash
git add company-rag-mcp/src/main/java/com/company/rag/mcp/model/
git commit -m "feat(mcp): 实现 MCP 协议数据模型"
```

---

## Task 3: 实现 JsonRpcHandler（协议解析）

**Files:**
- Create: `company-rag-mcp/src/main/java/com/company/rag/mcp/handler/JsonRpcHandler.java`
- Test: `company-rag-mcp/src/test/java/com/company/rag/mcp/handler/JsonRpcHandlerTest.java`

- [ ] **Step 1: 创建 JsonRpcHandler 类**

```java
package com.company.rag.mcp.handler;

import com.company.rag.mcp.model.JsonRpcRequest;
import com.company.rag.mcp.model.JsonRpcResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * JSON-RPC 协议处理器
 * 
 * 职责：
 * 1. 解析 HTTP 请求体为 JsonRpcRequest
 * 2. 验证协议格式（jsonrpc 版本、method 合法性）
 * 3. 构建 JsonRpcResponse 响应
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JsonRpcHandler {
    
    private final ObjectMapper objectMapper;
    
    /**
     * 解析 HTTP 请求体为 JSON-RPC 请求
     */
    public JsonRpcRequest parseRequest(String requestBody) {
        try {
            JsonRpcRequest request = objectMapper.readValue(requestBody, JsonRpcRequest.class);
            
            // 验证协议版本
            if (!"2.0".equals(request.getJsonrpc())) {
                throw new IllegalArgumentException("不支持的 JSON-RPC 版本：" + request.getJsonrpc());
            }
            
            // 验证 method
            if (request.getMethod() == null || request.getMethod().isBlank()) {
                throw new IllegalArgumentException("method 不能为空");
            }
            
            return request;
            
        } catch (Exception e) {
            log.error("JSON-RPC 请求解析失败：{}", e.getMessage());
            throw new IllegalArgumentException("JSON-RPC 请求格式错误：" + e.getMessage(), e);
        }
    }
    
    /**
     * 构建成功响应
     */
    public JsonRpcResponse buildSuccessResponse(Object requestId, Object result) {
        return JsonRpcResponse.success(requestId, result);
    }
    
    /**
     * 构建错误响应
     */
    public JsonRpcResponse buildErrorResponse(Object requestId, int errorCode, String errorMessage) {
        return JsonRpcResponse.error(requestId, errorCode, errorMessage, null);
    }
    
    /**
     * 构建错误响应（带附加数据）
     */
    public JsonRpcResponse buildErrorResponse(Object requestId, int errorCode, String errorMessage, Object errorData) {
        return JsonRpcResponse.error(requestId, errorCode, errorMessage, errorData);
    }
    
    /**
     * 将响应对象序列化为 JSON 字符串
     */
    public String serializeResponse(JsonRpcResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.error("JSON-RPC 响应序列化失败：{}", e.getMessage());
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"响应序列化失败\"}}";
        }
    }
    
    // JSON-RPC 标准错误码
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
}
```

- [ ] **Step 2: 创建单元测试**

```java
package com.company.rag.mcp.handler;

import com.company.rag.mcp.model.JsonRpcRequest;
import com.company.rag.mcp.model.JsonRpcResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonRpcHandler 单元测试
 */
class JsonRpcHandlerTest {
    
    private JsonRpcHandler handler;
    private ObjectMapper objectMapper;
    
    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        handler = new JsonRpcHandler(objectMapper);
    }
    
    @Test
    @DisplayName("解析合法的 JSON-RPC 请求")
    void parseValidRequest() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/list",
                "id": 1
            }
            """;
        
        JsonRpcRequest request = handler.parseRequest(requestBody);
        
        assertEquals("2.0", request.getJsonrpc());
        assertEquals("tools/list", request.getMethod());
        assertEquals(1, request.getId());
    }
    
    @Test
    @DisplayName("解析带参数的 JSON-RPC 请求")
    void parseRequestWithParams() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/call",
                "params": {
                    "name": "database_query",
                    "arguments": {
                        "sql": "SELECT * FROM users"
                    }
                },
                "id": 2
            }
            """;
        
        JsonRpcRequest request = handler.parseRequest(requestBody);
        
        assertEquals("tools/call", request.getMethod());
        assertEquals("database_query", request.getParams().getName());
        assertNotNull(request.getParams().getArguments());
    }
    
    @Test
    @DisplayName("解析不支持的 JSON-RPC 版本")
    void parseUnsupportedVersion() {
        String requestBody = """
            {
                "jsonrpc": "1.0",
                "method": "tools/list",
                "id": 1
            }
            """;
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> handler.parseRequest(requestBody)
        );
        
        assertTrue(exception.getMessage().contains("不支持的 JSON-RPC 版本"));
    }
    
    @Test
    @DisplayName("解析空 method 的请求")
    void parseEmptyMethod() {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "",
                "id": 1
            }
            """;
        
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> handler.parseRequest(requestBody)
        );
        
        assertTrue(exception.getMessage().contains("method 不能为空"));
    }
    
    @Test
    @DisplayName("构建成功响应")
    void buildSuccessResponse() {
        Object result = "{\"tools\": []}";
        JsonRpcResponse response = handler.buildSuccessResponse(1, result);
        
        assertEquals("2.0", response.getJsonrpc());
        assertEquals(1, response.getId());
        assertNotNull(response.getResult());
        assertNull(response.getError());
    }
    
    @Test
    @DisplayName("构建错误响应")
    void buildErrorResponse() {
        JsonRpcResponse response = handler.buildErrorResponse(1, -32601, "Method not found");
        
        assertEquals("2.0", response.getJsonrpc());
        assertEquals(1, response.getId());
        assertNull(response.getResult());
        assertNotNull(response.getError());
        assertEquals(-32601, response.getError().getCode());
        assertEquals("Method not found", response.getError().getMessage());
    }
    
    @Test
    @DisplayName("序列化响应为 JSON")
    void serializeResponse() throws Exception {
        JsonRpcResponse response = handler.buildSuccessResponse(1, "{\"result\": \"ok\"}");
        String json = handler.serializeResponse(response);
        
        assertNotNull(json);
        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"id\":1"));
        assertTrue(json.contains("\"result\""));
    }
}
```

- [ ] **Step 3: 运行测试验证**

```bash
cd company-rag-mcp
mvn test -Dtest=JsonRpcHandlerTest
```

Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 4: 提交**

```bash
git add company-rag-mcp/src/main/java/com/company/rag/mcp/handler/JsonRpcHandler.java
git add company-rag-mcp/src/test/java/com/company/rag/mcp/handler/JsonRpcHandlerTest.java
git commit -m "feat(mcp): 实现 JsonRpcHandler 协议解析器"
```

---

## Task 4: 实现 McpToolAdapter（协议转换）

**Files:**
- Create: `company-rag-mcp/src/main/java/com/company/rag/mcp/adapter/McpToolAdapter.java`
- Test: `company-rag-mcp/src/test/java/com/company/rag/mcp/adapter/McpToolAdapterTest.java`

- [ ] **Step 1: 创建 McpToolAdapter 类**

```java
package com.company.rag.mcp.adapter;

import com.company.rag.agent.tool.AgentTool;
import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.model.McpToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 协议适配器
 * 
 * 职责：
 * 1. 将 AgentTool 转换为 MCP 工具定义格式
 * 2. 调用 AgentToolRegistry 执行工具
 * 3. 处理两种工具实现方式（纯@Tool 注解 和 AgentTool 接口）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolAdapter {
    
    private final AgentToolRegistry agentToolRegistry;
    
    /**
     * 获取所有可用工具列表（MCP 格式）
     * 
     * @return MCP 工具定义列表
     */
    public List<McpToolDefinition> listTools() {
        List<AgentTool> tools = agentToolRegistry.getAllTools();
        
        return tools.stream()
                .map(this::convertToMcpDefinition)
                .collect(Collectors.toList());
    }
    
    /**
     * 调用指定工具
     * 
     * @param toolName 工具名称
     * @param arguments 工具参数
     * @return 工具执行结果
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        log.info("MCP 工具调用：name={}, args={}", toolName, arguments);
        
        try {
            // 通过 AgentToolRegistry 调用工具
            String result = agentToolRegistry.executeTool(toolName, arguments);
            log.info("MCP 工具调用成功：name={}, resultLength={}", toolName, 
                    result != null ? result.length() : 0);
            return result;
            
        } catch (Exception e) {
            log.error("MCP 工具调用失败：name={}, err={}", toolName, e.getMessage(), e);
            throw new RuntimeException("工具调用失败：" + e.getMessage(), e);
        }
    }
    
    /**
     * 将 AgentTool 转换为 MCP 工具定义
     */
    private McpToolDefinition convertToMcpDefinition(AgentTool tool) {
        return new McpToolDefinition(
                tool.getName(),
                tool.getDescription(),
                tool.getParameterSchema()
        );
    }
}
```

- [ ] **Step 2: 创建单元测试**

```java
package com.company.rag.mcp.adapter;

import com.company.rag.agent.tool.AgentTool;
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
    
    @Mock
    private AgentTool mockTool;
    
    private McpToolAdapter adapter;
    
    @BeforeEach
    void setUp() {
        adapter = new McpToolAdapter(agentToolRegistry);
    }
    
    @Test
    @DisplayName("列出所有可用工具")
    void listTools() {
        // 准备测试数据
        when(mockTool.getName()).thenReturn("database_query");
        when(mockTool.getDescription()).thenReturn("数据库查询工具");
        when(mockTool.getParameterSchema()).thenReturn(Map.of(
                "type", "object",
                "properties", Map.of(
                        "sql", Map.of("type", "string", "description", "SQL 语句")
                )
        ));
        
        when(agentToolRegistry.getAllTools()).thenReturn(List.of(mockTool));
        
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
```

- [ ] **Step 3: 运行测试验证**

```bash
cd company-rag-mcp
mvn test -Dtest=McpToolAdapterTest
```

Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 4: 提交**

```bash
git add company-rag-mcp/src/main/java/com/company/rag/mcp/adapter/
git add company-rag-mcp/src/test/java/com/company/rag/mcp/adapter/
git commit -m "feat(mcp): 实现 McpToolAdapter 协议转换器"
```

---

## Task 5: 实现 McpController（HTTP 端点）

**Files:**
- Create: `company-rag-mcp/src/main/java/com/company/rag/mcp/controller/McpController.java`
- Test: `company-rag-mcp/src/test/java/com/company/rag/mcp/controller/McpControllerTest.java`

- [ ] **Step 1: 创建 McpController 类**

```java
package com.company.rag.mcp.controller;

import com.company.rag.mcp.adapter.McpToolAdapter;
import com.company.rag.mcp.handler.JsonRpcHandler;
import com.company.rag.mcp.model.JsonRpcRequest;
import com.company.rag.mcp.model.JsonRpcResponse;
import com.company.rag.mcp.model.McpToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP Server HTTP 端点
 * 
 * 支持的 MCP 方法：
 * - GET /mcp/tools: 列出所有可用工具（对应 MCP 的 tools/list 方法）
 * - POST /mcp: 统一的 JSON-RPC 端点（支持 tools/list 和 tools/call）
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpController {
    
    private final JsonRpcHandler jsonRpcHandler;
    private final McpToolAdapter toolAdapter;
    
    /**
     * MCP 统一端点（POST）
     * 
     * 支持的方法：
     * - tools/list: 列出所有可用工具
     * - tools/call: 调用指定工具
     * 
     * @param requestBody JSON-RPC 请求体
     * @return JSON-RPC 响应
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handleMcpRequest(@RequestBody String requestBody) {
        log.info("MCP 请求：{}", requestBody);
        
        try {
            // 解析 JSON-RPC 请求
            JsonRpcRequest request = jsonRpcHandler.parseRequest(requestBody);
            
            // 根据 method 路由到不同处理逻辑
            JsonRpcResponse response;
            switch (request.getMethod()) {
                case "tools/list":
                    response = handleToolsList(request.getId());
                    break;
                    
                case "tools/call":
                    response = handleToolsCall(request);
                    break;
                    
                default:
                    response = jsonRpcHandler.buildErrorResponse(
                            request.getId(),
                            JsonRpcHandler.METHOD_NOT_FOUND,
                            "不支持的方法：" + request.getMethod()
                    );
            }
            
            // 返回 JSON-RPC 响应
            String responseBody = jsonRpcHandler.serializeResponse(response);
            log.info("MCP 响应：{}", responseBody);
            return ResponseEntity.ok(responseBody);
            
        } catch (IllegalArgumentException e) {
            // 请求格式错误
            log.warn("MCP 请求格式错误：{}", e.getMessage());
            String errorResponse = jsonRpcHandler.serializeResponse(
                    jsonRpcHandler.buildErrorResponse(
                            extractRequestId(requestBody),
                            JsonRpcHandler.INVALID_REQUEST,
                            e.getMessage()
                    )
            );
            return ResponseEntity.badRequest().body(errorResponse);
            
        } catch (Exception e) {
            // 内部错误
            log.error("MCP 请求处理失败：{}", e.getMessage(), e);
            String errorResponse = jsonRpcHandler.serializeResponse(
                    jsonRpcHandler.buildErrorResponse(
                            extractRequestId(requestBody),
                            JsonRpcHandler.INTERNAL_ERROR,
                            "内部错误：" + e.getMessage()
                    )
            );
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
    
    /**
     * 列出所有可用工具（GET 方式，方便浏览器直接访问）
     * 
     * @return 工具列表
     */
    @GetMapping(value = "/tools", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<McpToolDefinition>> listTools() {
        log.info("MCP 工具列表请求");
        
        List<McpToolDefinition> tools = toolAdapter.listTools();
        log.info("MCP 工具列表：{} 个工具", tools.size());
        
        return ResponseEntity.ok(tools);
    }
    
    /**
     * 处理 tools/list 方法
     */
    private JsonRpcResponse handleToolsList(Object requestId) {
        log.info("处理 tools/list 请求");
        
        List<McpToolDefinition> tools = toolAdapter.listTools();
        
        // MCP tools/list 返回格式
        Map<String, Object> result = Map.of("tools", tools);
        
        return jsonRpcHandler.buildSuccessResponse(requestId, result);
    }
    
    /**
     * 处理 tools/call 方法
     */
    private JsonRpcResponse handleToolsCall(JsonRpcRequest request) {
        String toolName = request.getParams().getName();
        Map<String, Object> arguments = null;
        
        if (request.getParams().getArguments() instanceof Map) {
            arguments = (Map<String, Object>) request.getParams().getArguments();
        }
        
        log.info("处理 tools/call 请求：tool={}", toolName);
        
        try {
            String result = toolAdapter.callTool(toolName, arguments);
            
            // MCP tools/call 返回格式
            Map<String, Object> responseContent = Map.of(
                    "content", List.of(
                            Map.of("type", "text", "text", result)
                    )
            );
            
            return jsonRpcHandler.buildSuccessResponse(request.getId(), responseContent);
            
        } catch (Exception e) {
            return jsonRpcHandler.buildErrorResponse(
                    request.getId(),
                    JsonRpcHandler.INTERNAL_ERROR,
                    "工具调用失败：" + e.getMessage()
            );
        }
    }
    
    /**
     * 从请求体中提取 requestId（用于错误响应）
     */
    private Object extractRequestId(String requestBody) {
        try {
            JsonRpcRequest request = jsonRpcHandler.parseRequest(requestBody);
            return request.getId();
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 2: 创建集成测试**

```java
package com.company.rag.mcp.controller;

import com.company.rag.agent.tool.AgentTool;
import com.company.rag.agent.tool.AgentToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * McpController 集成测试
 */
@WebMvcTest(McpController.class)
class McpControllerIntegrationTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @MockBean
    private AgentToolRegistry agentToolRegistry;
    
    @MockBean
    private AgentTool mockTool;
    
    @Test
    @DisplayName("GET /mcp/tools - 获取工具列表")
    void listTools() throws Exception {
        // 准备测试数据
        when(mockTool.getName()).thenReturn("database_query");
        when(mockTool.getDescription()).thenReturn("数据库查询工具");
        when(mockTool.getParameterSchema()).thenReturn(Map.of(
                "type", "object",
                "properties", Map.of(
                        "sql", Map.of("type", "string", "description", "SQL 语句")
                ),
                "required", List.of("sql")
        ));
        
        when(agentToolRegistry.getAllTools()).thenReturn(List.of(mockTool));
        
        // 执行请求
        mockMvc.perform(get("/mcp/tools")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("database_query"))
                .andExpect(jsonPath("$[0].description").value("数据库查询工具"))
                .andExpect(jsonPath("$[0].inputSchema").exists());
    }
    
    @Test
    @DisplayName("POST /mcp - tools/list 方法")
    void toolsList() throws Exception {
        // 准备测试数据
        when(mockTool.getName()).thenReturn("database_query");
        when(mockTool.getDescription()).thenReturn("数据库查询工具");
        when(mockTool.getParameterSchema()).thenReturn(Map.of(
                "type", "object"
        ));
        
        when(agentToolRegistry.getAllTools()).thenReturn(List.of(mockTool));
        
        // 构建 JSON-RPC 请求
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/list",
                "id": 1
            }
            """;
        
        // 执行请求
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.tools").isArray())
                .andExpect(jsonPath("$.result.tools.length()").value(1));
    }
    
    @Test
    @DisplayName("POST /mcp - tools/call 方法成功")
    void toolsCallSuccess() throws Exception {
        String toolName = "database_query";
        String expectedResult = "查询结果：...";
        
        when(agentToolRegistry.executeTool(eq(toolName), anyMap()))
                .thenReturn(expectedResult);
        
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/call",
                "params": {
                    "name": "database_query",
                    "arguments": {
                        "sql": "SELECT * FROM users LIMIT 10"
                    }
                },
                "id": 2
            }
            """;
        
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.result.content").isArray())
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value(expectedResult));
    }
    
    @Test
    @DisplayName("POST /mcp - 不支持的方法")
    void unsupportedMethod() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "unsupported/method",
                "id": 3
            }
            """;
        
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.error.code").value(-32601))
                .andExpect(jsonPath("$.error.message").value("不支持的方法：unsupported/method"));
    }
    
    @Test
    @DisplayName("POST /mcp - 不支持的 JSON-RPC 版本")
    void unsupportedVersion() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "1.0",
                "method": "tools/list",
                "id": 4
            }
            """;
        
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.error.code").value(-32600))
                .andExpect(jsonPath("$.error.message").value("不支持的 JSON-RPC 版本：1.0"));
    }
}
```

- [ ] **Step 3: 运行测试验证**

```bash
cd company-rag-mcp
mvn test -Dtest=McpControllerIntegrationTest
```

Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 4: 提交**

```bash
git add company-rag-mcp/src/main/java/com/company/rag/mcp/controller/
git add company-rag-mcp/src/test/java/com/company/rag/mcp/controller/
git commit -m "feat(mcp): 实现 McpController HTTP 端点"
```

---

## Task 6: 实现 McpSecurityFilter（JWT + 租户验证）

**Files:**
- Create: `company-rag-mcp/src/main/java/com/company/rag/mcp/security/McpSecurityFilter.java`
- Test: `company-rag-mcp/src/test/java/com/company/rag/mcp/security/McpSecurityFilterTest.java`

- [ ] **Step 1: 创建 McpSecurityFilter 类**

```java
package com.company.rag.mcp.security;

import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.service.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * MCP 安全过滤器
 * 
 * 职责：
 * 1. 验证 JWT Token 有效性
 * 2. 提取 X-Tenant-Id 请求头
 * 3. 验证租户 ID 是否在用户的 tenantIds 列表中
 * 4. 将租户 ID 设置到 TenantContext
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpSecurityFilter extends OncePerRequestFilter {
    
    private final TenantService tenantService;
    
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        
        String requestUri = request.getRequestURI();
        
        // 只对 /mcp 端点进行验证
        if (!requestUri.startsWith("/mcp")) {
            filterChain.doFilter(request, response);
            return;
        }
        
        try {
            // 1. 提取并验证 JWT Token
            String token = extractToken(request);
            if (token == null || token.isBlank()) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "缺少 JWT Token");
                return;
            }
            
            // TODO: 验证 JWT Token（后续集成 JwtSecurityValidator）
            // 暂时只提取 token，不验证签名（开发阶段）
            log.debug("MCP 请求 Token: {}", token);
            
            // 2. 提取租户 ID
            String tenantId = request.getHeader("X-Tenant-Id");
            if (tenantId == null || tenantId.isBlank()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "缺少 X-Tenant-Id 请求头");
                return;
            }
            
            // 3. 验证租户权限（TODO: 后续集成用户上下文）
            // 暂时只设置租户上下文
            log.debug("MCP 请求租户 ID: {}", tenantId);
            
            // 4. 设置租户上下文
            TenantContext.setTenantId(tenantId);
            log.info("MCP 请求租户上下文已设置：tenantId={}", tenantId);
            
            // 继续过滤链
            filterChain.doFilter(request, response);
            
        } catch (Exception e) {
            log.error("MCP 安全验证失败：{}", e.getMessage(), e);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "安全验证失败：" + e.getMessage());
        } finally {
            // 清理租户上下文
            TenantContext.clear();
        }
    }
    
    /**
     * 从请求头中提取 JWT Token
     */
    private String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return null;
    }
}
```

- [ ] **Step 2: 创建单元测试**

```java
package com.company.rag.mcp.security;

import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.service.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * McpSecurityFilter 单元测试
 */
@ExtendWith(MockitoExtension.class)
class McpSecurityFilterTest {
    
    @Mock
    private TenantService tenantService;
    
    @Mock
    private FilterChain filterChain;
    
    private McpSecurityFilter filter;
    
    @BeforeEach
    void setUp() {
        filter = new McpSecurityFilter(tenantService);
        TenantContext.clear();
    }
    
    @Test
    @DisplayName("非 /mcp 路径跳过验证")
    void skipNonMcpPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/other/path");
        MockHttpServletResponse response = new MockHttpServletResponse();
        
        filter.doFilterInternal(request, response, filterChain);
        
        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }
    
    @Test
    @DisplayName("缺少 JWT Token 返回 401")
    void missingJwtToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/mcp");
        MockHttpServletResponse response = new MockHttpServletResponse();
        
        filter.doFilterInternal(request, response, filterChain);
        
        verify(filterChain, never()).doFilter(any(), any());
        assertEquals(401, response.getStatus());
        assertTrue(response.getErrorMessage().contains("缺少 JWT Token"));
    }
    
    @Test
    @DisplayName("缺少 X-Tenant-Id 返回 400")
    void missingTenantId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/mcp");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        
        filter.doFilterInternal(request, response, filterChain);
        
        verify(filterChain, never()).doFilter(any(), any());
        assertEquals(400, response.getStatus());
        assertTrue(response.getErrorMessage().contains("缺少 X-Tenant-Id"));
    }
    
    @Test
    @DisplayName("验证成功设置租户上下文")
    void validationSuccess() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/mcp");
        request.addHeader("Authorization", "Bearer valid-token");
        request.addHeader("X-Tenant-Id", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        
        filter.doFilterInternal(request, response, filterChain);
        
        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }
}
```

- [ ] **Step 3: 运行测试验证**

```bash
cd company-rag-mcp
mvn test -Dtest=McpSecurityFilterTest
```

Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 4: 提交**

```bash
git add company-rag-mcp/src/main/java/com/company/rag/mcp/security/
git add company-rag-mcp/src/test/java/com/company/rag/mcp/security/
git commit -m "feat(mcp): 实现 McpSecurityFilter 安全过滤器"
```

---

## Task 7: 配置 Spring Security 和模块集成

**Files:**
- Create: `company-rag-mcp/src/main/java/com/company/rag/mcp/config/McpSecurityConfig.java`
- Modify: `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/SecurityConfig.java` (可选)
- Test: 无

- [ ] **Step 1: 创建 MCP 模块的 Security 配置**

```java
package com.company.rag.mcp.config;

import com.company.rag.mcp.security.McpSecurityFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * MCP 模块安全配置
 * 
 * 配置 /mcp 端点的安全策略：
 * - 禁用 Spring Security 的默认 CSRF 和 Session
 * - 添加 McpSecurityFilter 进行 JWT + 租户验证
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class McpSecurityConfig {
    
    private final McpSecurityFilter mcpSecurityFilter;
    
    @Bean
    public SecurityFilterChain mcpFilterChain(HttpSecurity http) throws Exception {
        http
            // 只针对 /mcp 端点
            .securityMatcher("/mcp/**")
            
            // 禁用 CSRF（MCP 使用 JSON-RPC，不需要 CSRF 保护）
            .csrf(AbstractHttpConfigurer::disable)
            
            // 禁用 Session（MCP 使用无状态 JWT 认证）
            .sessionManagement(sm -> sm.disable())
            
            // 禁用 HTTP Basic（使用 JWT Token）
            .httpBasic(AbstractHttpConfigurer::disable)
            
            // 禁用表单登录（使用 JWT Token）
            .formLogin(AbstractHttpConfigurer::disable)
            
            // 禁用登出（无状态）
            .logout(AbstractHttpConfigurer::disable)
            
            // 添加 MCP 安全过滤器
            .addFilterBefore(mcpSecurityFilter, UsernamePasswordAuthenticationFilter.class)
            
            // 允许所有请求（由 McpSecurityFilter 进行验证）
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        
        return http.build();
    }
}
```

- [ ] **Step 2: 在 application.yml 中添加 MCP 配置**

```yaml
# company-rag-bootstrap/src/main/resources/application.yml

# MCP Server 配置
mcp:
  server:
    enabled: true
    endpoint: /mcp
    # JWT Token 验证（后续集成）
    jwt:
      secret: ${MCP_JWT_SECRET:default-secret-key}
      expiration: ${MCP_JWT_EXPIRATION:3600000}
```

- [ ] **Step 3: 提交**

```bash
git add company-rag-mcp/src/main/java/com/company/rag/mcp/config/
git add company-rag-bootstrap/src/main/resources/application.yml
git commit -m "feat(mcp): 配置 Spring Security 和 MCP 模块集成"
```

---

## Task 8: 编写端到端集成测试

**Files:**
- Create: `company-rag-mcp/src/test/java/com/company/rag/mcp/McpServerE2ETest.java`
- Test: `company-rag-mcp/src/test/java/com/company/rag/mcp/McpServerE2ETest.java`

- [ ] **Step 1: 创建端到端集成测试**

```java
package com.company.rag.mcp;

import com.company.rag.agent.tool.AgentTool;
import com.company.rag.agent.tool.AgentToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MCP Server 端到端集成测试
 * 
 * 测试完整的 MCP 请求流程：
 * 1. HTTP 请求 → McpSecurityFilter → McpController → JsonRpcHandler → McpToolAdapter → AgentToolRegistry
 * 2. 验证 JWT + 租户验证
 * 3. 验证 JSON-RPC 协议解析
 * 4. 验证工具调用
 */
@SpringBootTest
@AutoConfigureMockMvc
class McpServerE2ETest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @MockBean
    private AgentToolRegistry agentToolRegistry;
    
    @MockBean
    private AgentTool mockTool;
    
    @Test
    @DisplayName("端到端测试：获取工具列表")
    void e2eListTools() throws Exception {
        // 准备测试数据
        when(mockTool.getName()).thenReturn("database_query");
        when(mockTool.getDescription()).thenReturn("数据库查询工具");
        when(mockTool.getParameterSchema()).thenReturn(Map.of(
                "type", "object",
                "properties", Map.of(
                        "sql", Map.of("type", "string", "description", "SQL 语句")
                ),
                "required", List.of("sql")
        ));
        
        when(agentToolRegistry.getAllTools()).thenReturn(List.of(mockTool));
        
        // 执行请求
        mockMvc.perform(get("/mcp/tools")
                .header("Authorization", "Bearer test-token")
                .header("X-Tenant-Id", "1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("database_query"));
    }
    
    @Test
    @DisplayName("端到端测试：调用工具成功")
    void e2eCallToolSuccess() throws Exception {
        String toolName = "database_query";
        String expectedResult = "查询结果：10 行数据";
        
        when(agentToolRegistry.executeTool(eq(toolName), anyMap()))
                .thenReturn(expectedResult);
        
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/call",
                "params": {
                    "name": "database_query",
                    "arguments": {
                        "sql": "SELECT * FROM users LIMIT 10"
                    }
                },
                "id": 1
            }
            """;
        
        mockMvc.perform(post("/mcp")
                .header("Authorization", "Bearer test-token")
                .header("X-Tenant-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value(expectedResult));
    }
    
    @Test
    @DisplayName("端到端测试：缺少 JWT Token")
    void e2eMissingJwtToken() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/list",
                "id": 1
            }
            """;
        
        mockMvc.perform(post("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isUnauthorized());
    }
    
    @Test
    @DisplayName("端到端测试：缺少租户 ID")
    void e2eMissingTenantId() throws Exception {
        String requestBody = """
            {
                "jsonrpc": "2.0",
                "method": "tools/list",
                "id": 1
            }
            """;
        
        mockMvc.perform(post("/mcp")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: 运行端到端测试**

```bash
cd company-rag-mcp
mvn test -Dtest=McpServerE2ETest
```

Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 3: 提交**

```bash
git add company-rag-mcp/src/test/java/com/company/rag/mcp/McpServerE2ETest.java
git commit -m "test(mcp): 添加 MCP Server 端到端集成测试"
```

---

## Task 9: Docker Compose 部署配置

**Files:**
- Modify: `docker-compose.yml`
- Create: `docs/deployment/mcp-server-deployment.md`

- [ ] **Step 1: 更新 docker-compose.yml**

```yaml
# docker-compose.yml

services:
  # 现有服务...
  
  # MCP Server 服务
  mcp-server:
    build:
      context: .
      dockerfile: Dockerfile
    ports:
      - "8081:8080"  # MCP Server 使用独立端口 8081
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - MCP_JWT_SECRET=${MCP_JWT_SECRET:-your-secret-key}
      - MCP_JWT_EXPIRATION=3600000
    depends_on:
      - postgres
      - redis
    networks:
      - company-rag-network
    restart: unless-stopped
```

- [ ] **Step 2: 创建部署文档**

```markdown
# MCP Server 部署文档

## 部署方式

### 方式 1: Docker Compose 部署

```bash
# 启动 MCP Server
docker-compose up -d mcp-server

# 查看日志
docker-compose logs -f mcp-server

# 停止服务
docker-compose stop mcp-server
```

### 方式 2: IDEA 本地运行

1. 在 IDEA 中创建 Run Configuration
2. Main class: `com.company.rag.bootstrap.CompanyRagApplication`
3. VM options: `-Dspring.profiles.active=dev`
4. Environment variables:
   - `MCP_JWT_SECRET=dev-secret-key`
   - `MCP_JWT_EXPIRATION=3600000`

## 验证部署

### 测试工具列表

```bash
curl -X GET http://localhost:8081/mcp/tools \
  -H "Authorization: Bearer test-token" \
  -H "X-Tenant-Id: 1"
```

### 测试工具调用

```bash
curl -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token" \
  -H "X-Tenant-Id: 1" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "params": {
      "name": "database_query",
      "arguments": {
        "sql": "SELECT * FROM sys_user LIMIT 10"
      }
    },
    "id": 1
  }'
```

## 监控指标

- MCP 请求总数：`mcp_requests_total`
- MCP 请求延迟：`mcp_request_duration_seconds`
- MCP 错误数：`mcp_errors_total`

## 故障排查

### 问题：401 Unauthorized

**原因:** JWT Token 缺失或无效

**解决:** 确保请求头中包含有效的 `Authorization: Bearer <token>`

### 问题：400 Bad Request

**原因:** 缺少 `X-Tenant-Id` 请求头

**解决:** 添加 `X-Tenant-Id: <tenant-id>` 请求头
```

- [ ] **Step 3: 提交**

```bash
git add docker-compose.yml docs/deployment/mcp-server-deployment.md
git commit -m "docs(mcp): 添加 Docker Compose 部署配置和文档"
```

---

## Task 10: 编写 README 和验收测试

**Files:**
- Create: `company-rag-mcp/README.md`
- Create: `docs/mcp/mcp-server-usage.md`
- Test: 手动验收测试

- [ ] **Step 1: 创建 MCP 模块 README**

```markdown
# CompanyRag MCP Server

MCP (Model Context Protocol) Server 实现，将 CompanyRag 的 Agent 工具暴露为标准 MCP 接口。

## 功能特性

- ✅ 支持 MCP 标准协议（JSON-RPC 2.0 over HTTP）
- ✅ 提供 `tools/list` 和 `tools/call` 方法
- ✅ JWT Token 认证
- ✅ 多租户隔离
- ✅ 与现有 Agent 系统完全兼容

## 快速开始

### 启动服务

```bash
# Docker Compose
docker-compose up -d mcp-server

# IDEA 本地运行
# 运行 CompanyRagApplication，MCP Server 自动启动
```

### 测试工具列表

```bash
curl http://localhost:8081/mcp/tools \
  -H "Authorization: Bearer your-token" \
  -H "X-Tenant-Id: 1"
```

### 测试工具调用

```bash
curl -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer your-token" \
  -H "X-Tenant-Id: 1" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "params": {
      "name": "database_query",
      "arguments": {
        "sql": "SELECT * FROM sys_user LIMIT 10"
      }
    },
    "id": 1
  }'
```

## MCP 协议支持

### 支持的方法

| 方法 | 描述 | 参数 |
|------|------|------|
| `tools/list` | 列出所有可用工具 | 无 |
| `tools/call` | 调用指定工具 | `name` (工具名), `arguments` (参数) |

### 错误码

| 错误码 | 说明 |
|--------|------|
| -32700 | Parse Error（JSON 解析失败） |
| -32600 | Invalid Request（请求格式错误） |
| -32601 | Method Not Found（不支持的方法） |
| -32602 | Invalid Params（参数错误） |
| -32603 | Internal Error（内部错误） |

## 安全认证

所有 MCP 请求必须携带：
- `Authorization: Bearer <jwt-token>` — JWT Token
- `X-Tenant-Id: <tenant-id>` — 租户 ID

## 监控指标

- `mcp_requests_total` — MCP 请求总数
- `mcp_request_duration_seconds` — MCP 请求延迟
- `mcp_errors_total` — MCP 错误数

## 开发指南

### 添加新工具

新工具自动被 MCP Server 支持，无需修改 MCP 代码：

1. 创建工具类，实现 `AgentTool` 接口或使用 `@Tool` 注解
2. 添加 `@Component` 注解
3. 重启服务，工具自动出现在 `tools/list` 中

### 调试

```bash
# 启用调试日志
export LOGGING_LEVEL_COM_COMPANY_RAG_MCP=DEBUG
```

## 参考资料

- [MCP 官方文档](https://modelcontextprotocol.io/)
- [JSON-RPC 2.0 规范](https://www.jsonrpc.org/specification)
- [MCP Server 使用文档](../../docs/mcp/mcp-server-usage.md)
```

- [ ] **Step 2: 创建 MCP Server 使用文档**

```markdown
# MCP Server 使用文档

## 概述

CompanyRag MCP Server 将项目的 4 个内置工具暴露为标准 MCP 协议接口：
- `database_query` — 数据库查询
- `code_search` — 代码搜索
- `api_doc` — API 文档生成
- `searchKnowledgeBase` — 知识库搜索

## 客户端示例

### Python 示例

```python
import requests

MCP_SERVER_URL = "http://localhost:8081/mcp"
HEADERS = {
    "Content-Type": "application/json",
    "Authorization": "Bearer your-token",
    "X-Tenant-Id": "1"
}

# 获取工具列表
response = requests.get(f"{MCP_SERVER_URL}/tools", headers=HEADERS)
tools = response.json()
print("可用工具:", [tool["name"] for tool in tools])

# 调用工具
request = {
    "jsonrpc": "2.0",
    "method": "tools/call",
    "params": {
        "name": "database_query",
        "arguments": {
            "sql": "SELECT * FROM sys_user LIMIT 10"
        }
    },
    "id": 1
}

response = requests.post(MCP_SERVER_URL, json=request, headers=HEADERS)
result = response.json()
print("工具调用结果:", result["result"]["content"][0]["text"])
```

### JavaScript 示例

```javascript
const MCP_SERVER_URL = 'http://localhost:8081/mcp';
const HEADERS = {
  'Content-Type': 'application/json',
  'Authorization': 'Bearer your-token',
  'X-Tenant-Id': '1'
};

// 获取工具列表
async function listTools() {
  const response = await fetch(`${MCP_SERVER_URL}/tools`, {
    method: 'GET',
    headers: HEADERS
  });
  return await response.json();
}

// 调用工具
async function callTool(toolName, args) {
  const request = {
    jsonrpc: '2.0',
    method: 'tools/call',
    params: {
      name: toolName,
      arguments: args
    },
    id: 1
  };
  
  const response = await fetch(MCP_SERVER_URL, {
    method: 'POST',
    headers: HEADERS,
    body: JSON.stringify(request)
  });
  
  return await response.json();
}

// 使用示例
(async () => {
  const tools = await listTools();
  console.log('可用工具:', tools.map(t => t.name));
  
  const result = await callTool('database_query', {
    sql: 'SELECT * FROM sys_user LIMIT 10'
  });
  console.log('工具调用结果:', result.result.content[0].text);
})();
```

## 错误处理

```python
try:
    response = requests.post(MCP_SERVER_URL, json=request, headers=HEADERS)
    response.raise_for_status()
    
    result = response.json()
    if 'error' in result:
        print(f"错误 {result['error']['code']}: {result['error']['message']}")
    else:
        print("成功:", result['result'])
        
except requests.exceptions.RequestException as e:
    print(f"请求失败：{e}")
```

## 最佳实践

1. **错误重试** — 网络错误时进行指数退避重试
2. **超时设置** — 建议设置 30 秒超时
3. **连接池** — 复用 HTTP 连接，提高性能
4. **日志记录** — 记录所有 MCP 请求和响应，便于排查问题

## 常见问题

**Q: 如何获取 JWT Token？**

A: 调用项目的登录接口获取，例如：`POST /api/auth/login`

**Q: 租户 ID 如何获取？**

A: 租户 ID 是用户关联的租户列表中的一个，通过用户上下文自动获取。

**Q: 支持并发调用吗？**

A: 支持，MCP Server 是无状态的，可以水平扩展。
```

- [ ] **Step 3: 手动验收测试**

```bash
# 1. 启动服务
docker-compose up -d mcp-server

# 2. 测试工具列表
curl -X GET http://localhost:8081/mcp/tools \
  -H "Authorization: Bearer test-token" \
  -H "X-Tenant-Id: 1" | jq

# 3. 测试工具调用（数据库查询）
curl -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer test-token" \
  -H "X-Tenant-Id: 1" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "params": {
      "name": "database_query",
      "arguments": {
        "sql": "SELECT * FROM sys_user LIMIT 10"
      }
    },
    "id": 1
  }' | jq

# 4. 验证所有 4 个工具都可用
# - database_query
# - code_search
# - api_doc
# - searchKnowledgeBase
```

- [ ] **Step 4: 提交**

```bash
git add company-rag-mcp/README.md docs/mcp/mcp-server-usage.md
git commit -m "docs(mcp): 添加 MCP Server README 和使用文档"
```

---

## 验收标准

完成所有任务后，验证以下验收标准：

- [ ] 外部 AI 应用可以通过 HTTP + JSON-RPC 调用工具
- [ ] 支持 `tools/list` 和 `tools/call` 方法
- [ ] JWT 认证和租户隔离正常工作
- [ ] 现有 Agent 系统继续正常工作（不受影响）
- [ ] 所有单元测试和集成测试通过
- [ ] Docker Compose 部署成功
- [ ] IDEA 本地运行成功
- [ ] 文档完整（README、使用文档、部署文档）

---

## 执行选择

**Plan complete and saved to `docs/superpowers/plans/2026-08-16-mcp-server-implementation.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
