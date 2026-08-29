# Parallel MCP 接入指南

## 概述

Parallel MCP 是由 Parallel AI 提供的免费 MCP 服务，无需 API Key，适合探索和轻量使用。
该服务提供 `parallel_search` 和 `parallel_fetch` 两个工具。

## 配置方式

### 1. 在 application-dev.yml 中添加配置

配置文件位置：`company-rag-bootstrap/src/main/resources/application-dev.yml`

```yaml
mcp:
  enabled: true
  clients:
    # Parallel MCP Server（官方免费服务，无需 API Key）
    # 提供 parallel_search 和 parallel_fetch 两个工具
    # 文档：https://parallel.ai/
    - id: parallel
      name: Parallel MCP Server
      url: https://search.parallel.ai/mcp
      enabled: true
      timeout: 60000  # 网络请求建议设置较长超时
```

### 2. 配置说明

| 配置项 | 值 | 说明 |
|--------|-----|------|
| id | parallel | 客户端唯一标识，工具名称会添加此前缀 |
| name | Parallel MCP Server | 描述性名称 |
| url | https://search.parallel.ai/mcp | Parallel MCP 服务端点 |
| enabled | true | 启用此客户端 |
| timeout | 60000 | 超时时间（毫秒），网络请求建议设置较长 |

### 3. 工具命名规则

Parallel MCP 提供的工具会自动添加客户端 ID 前缀：

- `parallel_search` → 注册为 `parallel_parallel_search`
- `parallel_fetch` → 注册为 `parallel_parallel_fetch`

**注意**：由于 Parallel 官方工具名称已经包含 `parallel_` 前缀，最终注册的工具名称会有双重前缀。

## 使用方式

### 通过 Agent 调用

启动应用后，ReactAgent 会自动发现并使用 Parallel MCP 提供的工具。

示例对话：
```
用户：帮我搜索一下最新的 AI 新闻
Agent：我将使用 parallel_search 工具为您搜索...
```

### 工具说明

1. **parallel_search** - 并行搜索工具
   - 功能：同时在多个来源搜索信息
   - 适用场景：需要广泛搜索的场景

2. **parallel_fetch** - 并行获取工具
   - 功能：同时获取多个 URL 的内容
   - 适用场景：需要批量获取网页内容的场景

## 技术实现

### 协议支持

当前项目的 `HttpMcpClient` 已支持 Parallel MCP 使用的协议：

- **传输方式**：HTTP + SSE (Server-Sent Events)
- **通信协议**：JSON-RPC 2.0
- **会话管理**：通过 `Mcp-Session-Id` 请求头管理会话

### 兼容性说明

Parallel MCP 使用标准的 MCP 协议，与现有实现完全兼容，无需修改代码。

关键特性：
- ✅ 支持 HTTP POST 请求
- ✅ 支持 SSE 响应格式解析
- ✅ 支持 Session ID 管理
- ✅ 支持 JSON-RPC 2.0 协议

## 验证步骤

### 1. 启动应用

```bash
cd D:/tmp/CompanyRag
mvn clean install
# 启动应用
```

### 2. 检查日志

查看应用启动日志，确认 MCP Client 初始化成功：

```
INFO  - MCP Client [parallel] 正在连接到服务器：https://search.parallel.ai/mcp
INFO  - MCP Client [parallel] 连接成功
INFO  - ReactAgent 初始化完成，Skills 和 Tools 已同时启用
```

### 3. 测试工具调用

通过 Agent 接口发送测试请求：

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: test-tenant" \
  -d '{"query": "使用 parallel_search 搜索 AI 技术"}'
```

### 4. 查看工具列表

启用 DEBUG 日志，查看注册的工具：

```yaml
logging:
  level:
    com.company.rag: DEBUG
```

日志中会显示：
```
DEBUG - 发现 MCP 工具：parallel_parallel_search
DEBUG - 发现 MCP 工具：parallel_parallel_fetch
```

## 故障排查

### 问题 1：连接失败

**症状**：日志显示 `MCP Client [parallel] 初始化失败`

**可能原因**：
- 网络连接问题
- Parallel 服务不可用
- 防火墙阻止请求

**解决方案**：
1. 检查网络连接：`curl https://search.parallel.ai/mcp`
2. 检查代理配置（如有）
3. 增加超时时间

### 问题 2：工具调用失败

**症状**：Agent 无法调用 Parallel 工具

**可能原因**：
- 工具名称不匹配
- 参数格式错误
- 服务端点变更

**解决方案**：
1. 检查工具注册列表
2. 查看 DEBUG 日志中的 JSON-RPC 请求/响应
3. 确认 Parallel 服务状态

### 问题 3：SSE 解析失败

**症状**：`SSE 响应中未找到 data 行`

**可能原因**：
- 响应格式不符合预期
- 网络中断导致响应不完整

**解决方案**：
1. 启用 DEBUG 日志查看原始响应
2. 检查网络稳定性
3. 增加超时时间

## 注意事项

1. **服务可用性**：Parallel MCP 是免费服务，不保证 SLA，生产环境慎用
2. **网络延迟**：跨境访问可能有较高延迟，建议增加超时时间
3. **请求限制**：免费服务可能有速率限制，注意观察错误响应
4. **数据安全**：不要通过 Parallel MCP 传输敏感信息
5. **工具名称**：Parallel 工具名称已有 `parallel_` 前缀，注册后会有双重前缀

## 参考资料

- [Parallel AI 官方文档](https://parallel.ai/)
- [MCP 协议规范](https://modelcontextprotocol.io/)
- [JSON-RPC 2.0 规范](https://www.jsonrpc.org/specification)
- [项目 MCP Client 实现文档](../../company-rag-mcp-client/README.md)

## 配置示例（完整版）

```yaml
# application-dev.yml
mcp:
  enabled: true
  clients:
    # 本地测试 MCP Server
    - id: custom
      name: 本地测试 MCP Server
      url: http://localhost:9001/mcp
      enabled: true
      timeout: 30000
    
    # Parallel MCP Server（免费服务）
    - id: parallel
      name: Parallel MCP Server
      url: https://search.parallel.ai/mcp
      enabled: true
      timeout: 60000
```

## 更新日志

- 2026-08-29：初始文档，完成 Parallel MCP 接入配置
