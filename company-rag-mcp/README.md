# MCP Server 模块

## 概述

`company-rag-mcp` 模块将 CompanyRag 的 4 个内部工具暴露为标准 MCP (Model Context Protocol) Server，使其他 AI 应用/框架能够通过标准 MCP 协议调用这些工具。

## 架构

```
外部 MCP Client
    │
    │ HTTP + JSON-RPC 2.0
    ▼
McpController (HTTP 端点)
    │
    │ 协议适配
    ▼
McpToolAdapter (协议转换层)
    │
    │ 工具调用
    ▼
AgentToolRegistry (工具注册中心)
    │
    │ 工具执行
    ▼
AgentTool 实现 (4 个工具)
```

## API 端点

### GET /mcp/tools

获取可用工具列表（REST 格式，便于调试）。

```bash
curl http://localhost:8080/mcp/tools
```

### POST /mcp

标准 MCP JSON-RPC 2.0 端点。

#### 列出工具

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "X-Tenant-Id: 1" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/list",
    "id": 1
  }'
```

#### 调用工具

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "X-Tenant-Id: 1" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "id": 2,
    "params": {
      "name": "knowledge_base_search",
      "arguments": {
        "query": "搜索内容"
      }
    }
  }'
```

## 安全

- **认证**：通过 `Authorization: Bearer <JWT>` 请求头传递 JWT Token
- **租户隔离**：通过 `X-Tenant-Id` 请求头传递租户 ID
- 安全过滤由 `McpSecurityFilter` 处理

## 部署

MCP 端点内建于主应用，无需额外部署配置。

### Docker Compose

```bash
docker-compose up -d
```

应用启动后，MCP 端点自动可用：`http://localhost:8080/mcp`

### IDEA 本地运行

直接运行 `BootstrapApplication`，MCP 端点自动可用。

## 测试

```bash
# 运行所有 MCP 模块测试
cd company-rag-mcp
mvn test

# 运行特定测试
mvn test -Dtest=JsonRpcHandlerTest
mvn test -Dtest=McpToolAdapterTest
mvn test -Dtest=McpControllerIntegrationTest
```

## 协议规范

- JSON-RPC 2.0 标准
- 支持方法：`tools/list`、`tools/call`
- 错误码遵循 JSON-RPC 2.0 规范
