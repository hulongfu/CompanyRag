# CompanyRag MCP Client 模块

## 概述

MCP Client 模块实现了 MCP（Model Context Protocol）协议客户端，支持连接外部 MCP Server 并将其提供的工具集成到 Agent 系统中。

## 功能特性

- ✅ 支持连接多个外部 MCP Server
- ✅ 自动发现和注册外部工具到 AgentToolRegistry
- ✅ 基于 HTTP + JSON-RPC 2.0 协议通信
- ✅ 统一的工具调用接口
- ✅ 支持自定义请求头和超时配置

## 模块结构

```
company-rag-mcp-client/
├── src/main/java/com/company/rag/mcp/client/
│   ├── McpClient.java              # MCP Client 接口
│   ├── HttpMcpClient.java          # 基于 WebClient 的 HTTP 实现
│   ├── McpClientRegistry.java      # Client 注册中心（管理多个连接）
│   ├── McpClientProperties.java    # 配置属性
│   ├── McpClientAutoConfig.java    # Spring 自动配置
│   └── ExternalMcpTool.java        # 外部工具适配器（适配为 AgentTool）
└── src/main/resources/
    └── application-mcp-example.yml # 配置示例
```

## 快速开始

### 1. 添加依赖

已在 `company-rag-bootstrap` 模块中自动包含。

### 2. 配置 MCP Clients

在 `application.yml` 中添加配置：

```yaml
mcp:
  enabled: true
  clients:
    - id: filesystem
      name: 文件系统 MCP Server
      url: http://localhost:3000/mcp
      enabled: true
      timeout: 30000
      headers:
        Authorization: "Bearer your-token-here"
```

### 3. 使用外部工具

启动后，外部 MCP Server 的工具会自动注册到 AgentToolRegistry：

- 工具名称格式：`{clientId}_{toolName}`
- 例如：`filesystem_read_file`、`filesystem_write_file`

Agent 可以通过标准工具调用接口使用这些工具。

## 架构设计

### 核心组件

1. **McpClient** - MCP 协议客户端接口
   - 定义连接、断开、工具列表、工具调用等基础操作

2. **HttpMcpClient** - HTTP 实现
   - 基于 Spring WebClient 实现异步 HTTP 通信
   - 实现 JSON-RPC 2.0 协议格式

3. **McpClientRegistry** - 注册中心
   - 管理多个 MCP Client 连接
   - 提供统一的工具调用接口
   - 自动将外部工具注册到 AgentToolRegistry

4. **ExternalMcpTool** - 适配器
   - 将外部 MCP 工具适配为 AgentTool 接口
   - 工具名称前缀化避免冲突

### 数据流

```
Agent → AgentToolRegistry → ExternalMcpTool → McpClientRegistry → HttpMcpClient → 外部 MCP Server
```

## JSON-RPC 协议

### tools/list 请求

```json
{
  "jsonrpc": "2.0",
  "method": "tools/list",
  "id": "req-123-filesystem",
  "params": null
}
```

### tools/call 请求

```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "id": "req-123-filesystem",
  "params": {
    "name": "read_file",
    "arguments": {
      "path": "/path/to/file.txt"
    }
  }
}
```

## 配置说明

| 配置项 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| mcp.enabled | boolean | 否 | 是否启用 MCP Client，默认 true |
| mcp.clients[].id | string | 是 | 客户端唯一标识（工具名称前缀） |
| mcp.clients[].name | string | 否 | 描述性名称 |
| mcp.clients[].url | string | 是 | MCP Server HTTP 端点 |
| mcp.clients[].enabled | boolean | 否 | 是否启用此客户端，默认 true |
| mcp.clients[].timeout | int | 否 | 请求超时（毫秒），默认 30000 |
| mcp.clients[].headers | map | 否 | 自定义请求头 |

## 工具命名规则

为避免工具名称冲突，外部 MCP Server 的工具会自动添加客户端 ID 前缀：

```
原始工具名：read_file
客户端 ID: filesystem
注册名称：filesystem_read_file
```

## 错误处理

- 连接失败：记录错误日志，跳过该客户端
- 工具调用失败：返回错误信息字符串
- 协议错误：抛出 RuntimeException

## 测试

运行单元测试：

```bash
mvn test -pl company-rag-mcp-client
```

## 示例 MCP Servers

### 文件系统 MCP Server

```yaml
mcp:
  clients:
    - id: filesystem
      name: 文件系统 MCP Server
      url: http://localhost:3000/mcp
      enabled: true
```

### GitHub MCP Server

```yaml
mcp:
  clients:
    - id: github
      name: GitHub MCP Server
      url: https://api.github.com/mcp
      enabled: true
      headers:
        Authorization: "Bearer ${GITHUB_TOKEN}"
```

## 注意事项

1. **网络连接**：确保应用可以访问外部 MCP Server 的地址
2. **认证信息**：通过 headers 配置传递认证 token
3. **超时设置**：根据网络情况调整 timeout
4. **工具冲突**：不同客户端的工具名称不要冲突
5. **错误日志**：关注启动时的初始化日志

## 开发指南

### 添加新的 MCP Client 实现

1. 实现 `McpClient` 接口
2. 在 `McpClientAutoConfig` 中注册
3. 添加相应配置属性

### 调试技巧

1. 启用 DEBUG 日志查看 JSON-RPC 请求/响应
2. 使用 WireMock 模拟外部 MCP Server
3. 检查 AgentToolRegistry 中的工具列表

## 相关文档

- [MCP Server 实现文档](../company-rag-mcp/README.md)
- [Agent Tool 系统](../company-rag-agent/README.md)
- [JSON-RPC 2.0 规范](https://www.jsonrpc.org/specification)
