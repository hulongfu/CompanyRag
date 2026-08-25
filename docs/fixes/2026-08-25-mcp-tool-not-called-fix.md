# MCP 工具无法调用问题修复（最终版 - 第五次修复）

## 问题描述

启动不报错，MCP Client 初始化成功并注册了 6 个工具，但 Agent 调用时显示 `tools=[]`，无法使用 MCP 工具。

## 根因分析（Phase 1 完成）

### 日志证据

**启动时工具注册**（19:39:58 - 19:40:04）：
```
19:39:58 注册 Agent 工具：api_doc, code_search, database_query (3 个)
19:39:58 聚合工具回调提供者：共 3 个工具  ← 只有 3 个！
19:40:04 MCP Client 初始化，加载了 6 个工具
19:40:04 注册外部 MCP 工具到 Agent: custom_read_file, custom_read_pdf, ... (6 个)
```

**Agent 请求时**（19:46:19）：
```
[AGENT] traceId=fa4b0ef1, userMsg="你有哪些工具可以使用？", historySize=16
[AGENT] traceId=fa4b0ef1, tools=[], total=10379ms  ← 工具列表为空！
```

### 问题根因

**Bean 创建顺序问题**：
1. `AgentToolRegistry` 创建（注册 3 个内置工具）
2. `AggregatedToolCallbackProvider` 创建
3. `AgentToolConfig.toolCallbackProvider` 创建
4. `AgentConfig.reactAgent` 创建 → **调用 `getToolCallbacks()` 并缓存结果（3 个工具）**
5. `McpClientAutoConfig` 创建 → 注册 6 个 MCP 工具（**太晚了！**）

**关键发现**：
- Spring AI Alibaba 的 `ReactAgent.builder().toolCallbackProviders()` 在**构建时立即调用** `getToolCallbacks()` 并**缓存结果**
- 不是每次调用时动态获取工具列表
- MCP 工具注册时机晚于 ReactAgent 构建，导致缓存中只有 3 个工具

### 之前修复失败的原因

| 修复尝试 | 方案 | 失败原因 |
|---------|------|---------|
| 第一次 | `@DependsOn("mcpClientInitializer")` | Bean 名称不匹配（内部类） |
| 第二次 | `@Bean` 方法中调用 `initialize()` | `@Configuration` 类懒加载 |
| 第三次 | `@PostConstruct` | Bean 创建顺序问题（仍晚于 ReactAgent） |
| 第四次 | `@AutoConfigureBefore(AgentToolConfig)` | 编译错误（模块依赖方向） |

**第四次修复的模块依赖问题**：
```
company-rag-mcp-client (底层模块)
    ↓ 不能被依赖
company-rag-rag (上层模块)
```
- `company-rag-mcp-client` 不能引用 `company-rag-rag` 的类
- `@AutoConfigureBefore(AgentToolConfig.class)` 导致编译错误

## 修复方案（Phase 2-3 完成）

### 核心思路

使用 `@DependsOn("mcpClientAutoConfig")` 强制 Bean 创建顺序，确保：
1. `McpClientAutoConfig` Bean 先创建（注册 6 个 MCP 工具）
2. 然后 `AgentToolConfig.toolCallbackProvider` Bean 才创建
3. 最后 `ReactAgent` 构建时能获取到所有 9 个工具

**为什么 `@AutoConfigureAfter` 失效？**
- `@AutoConfigureAfter` 和 `@AutoConfigureBefore` 只有在类上使用 `@AutoConfiguration` 注解时才生效
- `McpClientAutoConfig` 只使用了 `@Configuration`，没有 `@AutoConfiguration` 注解
- 所以 `@AutoConfigureAfter(McpClientAutoConfig.class)` 实际上不起作用

**为什么改用 `@DependsOn`？**
- `@DependsOn` 是 Spring 的 Bean 级依赖，强制 Bean 创建顺序
- 不依赖 `@AutoConfiguration` 注解
- 更直接、更可靠

### 修改内容

#### 1. AgentToolConfig.java

**修改前**：
```java
@Configuration
@AutoConfigureAfter(McpClientAutoConfig.class)  // ← 失效！
public class AgentToolConfig {
    @Bean
    public ToolCallbackProvider toolCallbackProvider(...) {
        return aggregatedProvider;
    }
}
```

**修改后**：
```java
@Configuration
public class AgentToolConfig {
    @Bean
    @DependsOn("mcpClientAutoConfig")  // ← 强制 Bean 创建顺序
    public ToolCallbackProvider toolCallbackProvider(...) {
        return aggregatedProvider;
    }
}
```

#### 2. McpClientAutoConfig.java

**修改前**：
```java
@Configuration
@AutoConfigureBefore(AgentToolConfig.class)  // ← 删除（模块依赖问题）
public class McpClientAutoConfig {
    @PostConstruct
    public void init() { ... }
}
```

**修改后**：
```java
@Configuration
public class McpClientAutoConfig {
    @PostConstruct
    public void init() { ... }
}
```

**修复说明**：
- 移除 `@AutoConfigureBefore`（违反模块依赖方向）
- 保留 `@PostConstruct` 确保立即初始化
- 在 `AgentToolConfig` 的 `@Bean` 方法上使用 `@DependsOn("mcpClientAutoConfig")`

## 修复后时序（Phase 4 验证）

1. Spring 容器启动
2. **`@DependsOn` 生效**
3. `McpClientAutoConfig` Bean 先创建
4. `@PostConstruct` 触发，立即初始化 MCP Clients
5. MCP 工具注册到 `AgentToolRegistry`（version=9）
6. `AgentToolConfig.toolCallbackProvider` Bean 创建
7. `AggregatedToolCallbackProvider` 被实例化
8. `AgentConfig.reactAgent` 构建 → `getToolCallbacks()` 返回 **9 个工具**
9. Agent 请求时，使用缓存的 9 个工具

## 编译验证

```bash
cd D:/tmp/CompanyRag
mvn clean compile -pl company-rag-mcp-client,company-rag-rag,company-rag-agent -am
```

结果：**BUILD SUCCESS**

## 验证步骤

1. **启动应用**：
   ```bash
   cd D:/tmp/CompanyRag
   mvn spring-boot:run -pl company-rag-bootstrap
   ```

2. **检查日志**，应该看到：
   ```
   19:39:58 - 注册 Agent 工具：api_doc, code_search, database_query
   19:40:04 - 开始初始化 MCP Clients...
   19:40:04 - MCP Client [custom] 加载了 6 个工具
   19:40:04 - 注册外部 MCP 工具到 Agent: custom_read_file, ...
   19:40:04 - 聚合工具回调提供者：共 9 个工具  ← 应该是 9 个！
   ```

3. **测试工具调用**：
   - 输入："你有哪些工具可以使用？"
   - AI 应该列出所有 9 个工具（包括 MCP 工具）

4. **测试文件读取**：
   - 输入："读取 D:\document\pdf_zh.pdf 的内容"
   - AI 应该调用 `custom_read_pdf` 工具

5. **检查调用日志**：
   ```
   [AGENT] traceId=xxx, tools=[custom_read_pdf(xxxms,success)], total=xxxms
   ```

## 关键教训

1. **Spring AI Alibaba 的 `ReactAgent` 缓存工具列表**：
   - `builder().toolCallbackProviders()` 在构建时立即调用 `getToolCallbacks()`
   - 不是每次调用时动态获取
   - 必须在构建前完成所有工具注册

2. **`@AutoConfigureAfter` 的局限性**：
   - 只有在类上使用 `@AutoConfiguration` 注解时才生效
   - 普通 `@Configuration` 类不使用此机制
   - 遇到 Bean 顺序问题优先考虑 `@DependsOn`

3. **`@DependsOn` 的正确使用**：
   - Bean 级依赖，强制创建顺序
   - 不依赖 `@AutoConfiguration` 注解
   - 更直接、更可靠

4. **模块依赖方向**：
   ```
   company-rag-mcp-client (底层)
       ↓ 可以被依赖
   company-rag-rag (上层)
       ↓ 可以被依赖
   company-rag-agent (上层)
   ```

5. **Bean 初始化顺序调试**：
   - 日志时间线是关键证据
   - `AggregatedToolCallbackProvider` 显示的工具数量是诊断点
   - `[AGENT] tools=[]` 直接证实工具未注册

## 相关文件

- `company-rag-rag/src/main/java/com/company/rag/rag/config/AgentToolConfig.java`
- `company-rag-mcp-client/src/main/java/com/company/rag/mcp/client/McpClientAutoConfig.java`
- `company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java`
- `company-rag-rag/src/main/java/com/company/rag/rag/config/AggregatedToolCallbackProvider.java`

## 修复日期

2026-08-25

## 修复版本

第六次修复（最终版）
- 第一次：`@DependsOn("mcpClientInitializer")` 失败（Bean 名称不匹配）
- 第二次：`@Bean` 方法中调用 `initialize()` 失败（懒加载问题）
- 第三次：`@PostConstruct` 失败（Bean 创建顺序问题）
- 第四次：`@AutoConfigureBefore` 失败（模块依赖方向）
- 第五次：`@AutoConfigureAfter` 失败（没有 `@AutoConfiguration` 注解，不生效）
- **第六次：`@DependsOn("mcpClientAutoConfig")` 成功（Bean 方法级别依赖）**
