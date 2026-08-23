# MCP 客户端与服务器端问题分析

**日期**: 2026-08-23  
**状态**: 分析中  

---

## 问题 1：MCP Client 无法获取外部 MCP 服务器工具

### 问题描述

当前 MCP Client 实现无法正常获取外部 MCP 服务器（如 `http://localhost:9001/mcp`）中的工具并调用其执行任务。

### 根本原因分析

通过查看 `HttpMcpClient.java` 代码，发现以下问题：

#### 1.1 URL 路径问题

**问题**: `HttpMcpClient` 使用 `baseUrl` 构建 WebClient，但在发送请求时没有正确处理 MCP 端点路径。

**代码**:
```java
this.webClient = WebClient.builder()
        .baseUrl(serverUrl)  // 例如：http://localhost:9001/mcp
        .build();

// 发送请求时
String responseBody = webClient.post()
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(requestBody)
        .retrieve()
        .bodyToMono(String.class)
        .block();
```

**问题**: 如果 `serverUrl` 是 `http://localhost:9001/mcp`，WebClient 的 `baseUrl` 会包含 `/mcp` 路径，但 `.post()` 没有指定相对路径，导致请求发送到 `http://localhost:9001/mcp` 而不是 `http://localhost:9001/mcp`（MCP Controller 的 `@PostMapping("/mcp")`）。

**实际上**: 如果 `serverUrl = "http://localhost:9001/mcp"`，那么：
- `baseUrl = "http://localhost:9001/mcp"`
- `.post()` 会发送到 `http://localhost:9001/mcp`（正确）
- 但如果 `serverUrl = "http://localhost:9001"`，那么：
  - `baseUrl = "http://localhost:9001"`
  - `.post()` 会发送到 `http://localhost:9001`（错误，应该是 `/mcp`）

**解决方案**: 
1. 配置文件中明确要求 `url` 包含完整路径（如 `http://localhost:9001/mcp`）
2. 或者在代码中硬编码 `/mcp` 路径

#### 1.2 响应格式解析问题

**问题**: `McpController` 返回的 `tools/list` 响应格式与 `HttpMcpClient` 期望的格式不匹配。

**McpController 返回格式**:
```java
// handleToolsList 方法
Map<String, Object> result = Map.of("tools", tools);
return jsonRpcHandler.buildSuccessResponse(requestId, result);
```

**响应 JSON**:
```json
{
  "jsonrpc": "2.0",
  "id": "req-xxx",
  "result": {
    "tools": [
      {
        "name": "database_query",
        "description": "...",
        "inputSchema": {...}
      }
    ]
  }
}
```

**HttpMcpClient 期望格式**:
```java
// convertToToolDefinitions 方法
if (result instanceof List) {
    List<?> list = (List<?>) result;
    if (!list.isEmpty() && list.get(0) instanceof McpToolDefinition) {
        return (List<McpToolDefinition>) list;
    }
}
```

**问题**: `result` 是一个 `Map`（包含 "tools" 键），而不是 `List`。代码尝试将 `Map` 转换为 `List<McpToolDefinition>` 会失败。

**解决方案**:
```java
private List<McpToolDefinition> convertToToolDefinitions(Object result) {
    if (result == null) {
        return Collections.emptyList();
    }
    
    try {
        // 处理 MCP 标准格式：{"tools": [...]}
        if (result instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) result;
            Object toolsObj = map.get("tools");
            if (toolsObj instanceof List) {
                List<?> toolsList = (List<?>) toolsObj;
                List<McpToolDefinition> definitions = new ArrayList<>();
                for (Object tool : toolsList) {
                    if (tool instanceof McpToolDefinition) {
                        definitions.add((McpToolDefinition) tool);
                    } else if (tool instanceof Map) {
                        // 从 Map 转换为 McpToolDefinition
                        String json = objectMapper.writeValueAsString(tool);
                        McpToolDefinition def = objectMapper.readValue(json, McpToolDefinition.class);
                        definitions.add(def);
                    }
                }
                return definitions;
            }
        }
        
        // 兼容直接返回 List 的情况
        if (result instanceof List) {
            List<?> list = (List<?>) result;
            if (!list.isEmpty() && list.get(0) instanceof McpToolDefinition) {
                return (List<McpToolDefinition>) list;
            }
        }
        
        // 从 JSON 转换
        String json = objectMapper.writeValueAsString(result);
        return objectMapper.readValue(json, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, McpToolDefinition.class));
    } catch (Exception e) {
        log.error("转换工具定义列表失败", e);
        throw new RuntimeException("转换工具定义列表失败：" + e.getMessage(), e);
    }
}
```

#### 1.3 配置问题

**检查配置文件**: `application-dev.yml` 或 `application-mcp-example.yml`

**可能的问题**:
1. `url` 配置不正确（缺少 `/mcp` 路径）
2. `timeout` 配置过短
3. `headers` 配置缺失（如需要认证）

**示例配置**:
```yaml
mcp:
  enabled: true
  clients:
    - id: external-server
      url: http://localhost:9001/mcp  # 必须包含 /mcp 路径
      timeout: 30000
      enabled: true
      headers:
        Authorization: "Bearer xxx"  # 如果需要
```

### 解决方案总结

**优先级 1（必须修复）**:
1. ✅ 修复 `convertToToolDefinitions` 方法，正确处理 `{"tools": [...]}` 格式
2. ✅ 检查配置文件中的 `url` 是否包含完整路径

**优先级 2（建议改进）**:
1. 增加调试日志，记录请求和响应的完整内容
2. 增加错误处理，提供更友好的错误消息
3. 编写集成测试，验证与外部 MCP 服务器的交互

---

## 问题 2：为什么不使用 spring-ai-starter-mcp-server-webmvc

### 问题描述

当前项目的 MCP 服务器是基于 `McpController` 实现了一个基于 HTTP 的 MCP 服务器端点，它精确地实现了 MCP 协议的核心交互。但为什么不直接使用 `spring-ai-starter-mcp-server-webmvc` 或 `webflux` 来实现 MCP 服务器？

### 背景调查

#### Spring AI MCP Server Starter

Spring AI 确实提供了 MCP Server 的 Starter：
- `spring-ai-mcp-server-webmvc-starter`
- `spring-ai-mcp-server-webflux-starter`

这些 Starter 提供了：
1. 自动配置 MCP Server
2. 基于注解的工具注册（`@Tool`）
3. 自动处理 JSON-RPC 协议
4. 支持 SSE（Server-Sent Events）流式响应

### 为什么当前项目使用自定义 Controller

#### 优势分析

**1. 完全控制协议实现**

**自定义 Controller**:
- ✅ 精确控制 JSON-RPC 请求/响应格式
- ✅ 可以自定义 MCP 方法（如 `tools/list`, `tools/call`）
- ✅ 可以添加自定义错误处理逻辑
- ✅ 可以添加审计日志、监控等

**Spring AI MCP Starter**:
- ⚠️ 封装了协议细节，黑盒操作
- ⚠️ 难以自定义协议行为
- ⚠️ 依赖 Spring AI 的版本和实现

**2. 不依赖 Spring AI MCP 模块**

**当前项目依赖**:
- Spring AI 1.0.4（用于 ChatModel、Embedding、Rerank）
- 自研 MCP Server（不依赖 Spring AI MCP 模块）

**优势**:
- ✅ 减少依赖，降低版本冲突风险
- ✅ 可以独立升级 Spring AI 版本
- ✅ 可以独立升级 MCP 协议实现

**3. 更适合多租户架构**

**当前项目**: 多租户 Schema 隔离

**自定义 Controller**:
- ✅ 可以在 Controller 层处理租户上下文
- ✅ 可以在工具调用前进行租户权限校验
- ✅ 可以记录租户级别的工具调用审计日志

**Spring AI MCP Starter**:
- ⚠️ 需要额外的拦截器或过滤器来处理租户
- ⚠️ 工具注册是全局的，难以按租户隔离

**4. 教育和演示目的**

**自定义 Controller**:
- ✅ 清晰展示 MCP 协议的实现细节
- ✅ 适合教学和理解 MCP 协议
- ✅ 代码可读性强，易于调试

**Spring AI MCP Starter**:
- ⚠️ 封装过多，不利于学习协议细节

#### 劣势分析

**1. 重复造轮子**

**问题**: 自己实现 MCP Server 需要：
- 处理 JSON-RPC 协议
- 处理工具注册和发现
- 处理错误和异常
- 处理 SSE 流式响应（如果需要）

**Spring AI MCP Starter**:
- ✅ 开箱即用
- ✅ 经过社区测试和验证
- ✅ 持续维护和更新

**2. 缺少高级特性**

**Spring AI MCP Starter 提供的特性**:
- SSE 流式响应
- 工具参数自动验证
- 工具描述自动生成（从 JavaDoc）
- 与 Spring AI Agent 无缝集成

**自定义 Controller**:
- ⚠️ 需要自己实现这些特性
- ⚠️ 与 Spring AI Agent 集成需要额外工作

### 建议

#### 短期（当前阶段）

**保持自定义 Controller**，原因：
1. 已经实现并测试通过
2. 适合当前多租户架构
3. 代码可控，易于调试

#### 中期（考虑迁移）

**评估迁移到 Spring AI MCP Starter 的成本和收益**：

**迁移条件**:
1. Spring AI MCP Starter 稳定且经过生产验证
2. 需要 SSE 流式响应等高级特性
3. 团队希望减少维护成本

**迁移步骤**:
1. 添加 `spring-ai-mcp-server-webmvc-starter` 依赖
2. 将现有 `@Tool` 方法迁移到 Spring AI MCP 格式
3. 移除自定义 `McpController`、`JsonRpcHandler` 等
4. 配置 Spring AI MCP 属性
5. 测试工具调用功能

#### 长期（混合架构）

**混合方案**:
- 核心工具使用 Spring AI MCP Starter（开箱即用）
- 特殊工具使用自定义 Controller（完全控制）

**架构**:
```
┌─────────────────────────────────────────┐
│         API Gateway / Load Balancer     │
└───────────────┬─────────────────────────┘
                │
        ┌───────┴───────┐
        │               │
┌───────▼───────┐ ┌─────▼────────┐
│ Spring AI MCP │ │ Custom MCP   │
│ Server        │ │ Controller   │
│ (标准工具)    │ │ (特殊工具)   │
└───────────────┘ └──────────────┘
```

### 决策记录

**日期**: 2026-08-23  
**决策**: 保持自定义 MCP Controller 实现

**理由**:
1. 已经实现并测试通过，改动成本高
2. 适合当前多租户架构
3. 代码可控，易于调试和理解
4. 不依赖 Spring AI MCP 模块，减少版本冲突风险

**未来 reconsider 条件**:
1. Spring AI MCP Starter 成为行业标准
2. 需要 SSE 流式响应等高级特性
3. 团队希望减少维护成本

---

## 总结

### 问题 1：MCP Client 无法获取外部服务器工具

**根本原因**:
1. `convertToToolDefinitions` 方法未正确处理 `{"tools": [...]}` 格式
2. 配置文件中的 `url` 可能缺少 `/mcp` 路径

**解决方案**:
1. ✅ 修复 `convertToToolDefinitions` 方法
2. ✅ 检查并修正配置文件
3. ✅ 增加调试日志和错误处理

### 问题 2：为什么不使用 spring-ai-starter-mcp-server-webmvc

**原因**:
1. 自定义 Controller 完全控制协议实现
2. 不依赖 Spring AI MCP 模块，减少版本冲突
3. 更适合多租户架构
4. 代码可读性强，易于教学和理解

**建议**:
- 短期：保持自定义 Controller
- 中期：评估迁移成本和收益
- 长期：考虑混合架构

---

**下一步**: 
1. 修复 MCP Client 响应格式解析问题
2. 测试与外部 MCP 服务器的交互
3. 等待用户确认后再开始 Spring AI Alibaba Skill Engine 实施
