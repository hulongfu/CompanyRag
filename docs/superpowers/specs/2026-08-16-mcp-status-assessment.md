# MCP 现状评估设计文档

**日期:** 2026-08-16  
**作者:** CompanyRag Team  
**状态:** 待评审  
**阶段:** 阶段 1/3（现状评估 → MCP Server 实现 → MCP Client 集成）

---

## 1. 概述

### 1.1 背景
Model Context Protocol (MCP) 是一个开放协议标准，用于 AI 应用与外部工具/数据源之间的标准化通信。当前项目已有内部的 Agent 工具系统（`AgentTool` 接口 + 4 个工具实现），但不是标准 MCP 协议，无法被其他 MCP Client 调用，也无法调用外部 MCP Server 的工具。

### 1.2 目标
本阶段目标：全面分析当前系统与标准 MCP 协议的差距，为后续 MCP Server 和 MCP Client 实现提供技术选型和架构指导。

**包含:**
- MCP 协议介绍和核心概念
- 当前 AgentTool 系统分析
- 5 个维度的差距分析（协议、技术、工具、架构、安全）
- 技术选型和推荐方案
- 高层次架构设计
- 阶段 2/3 实施路线图

**不包含:**
- MCP Server 的具体实现细节（阶段 2）
- MCP Client 的具体实现细节（阶段 3）
- 代码实现

### 1.3 成功标准
- [ ] 清晰解释 MCP 协议核心概念
- [ ] 详细分析当前系统与 MCP 的差距
- [ ] 给出明确的技术选型和推荐方案
- [ ] 架构设计得到用户批准
- [ ] 阶段 2/3 路线图清晰可行

---

## 2. MCP 协议介绍

### 2.1 什么是 MCP？

**Model Context Protocol (MCP)** 是一个开放协议标准，由 Anthropic 等公司推动，用于：
- **统一 AI 应用与工具的通信方式** — 标准化的工具描述、调用、返回格式
- **解耦 AI 模型与工具实现** — AI 应用无需关心工具的具体实现细节
- **支持多种通信方式** — stdio（本地进程间）、SSE（远程流式）、HTTP + JSON-RPC（远程双向）

### 2.2 核心概念

| 概念 | 说明 | 示例 |
|------|------|------|
| **MCP Server** | 提供工具的服务端，暴露工具列表和执行接口 | 当前项目的 Agent 工具系统改造后 |
| **MCP Client** | 调用工具的消费端，发现工具并发起调用 | Claude Desktop、其他 AI 应用 |
| **Tool** | 可被调用的功能单元，有名称、描述、参数 Schema | `database_query`, `code_search` |
| **JSON-RPC 2.0** | MCP 使用的通信协议，基于 JSON 的远程过程调用协议 | `{"jsonrpc": "2.0", "method": "tools/call", ...}` |
| **Transport** | 通信传输层，支持 stdio/SSE/HTTP | HTTP + JSON-RPC（本阶段推荐） |

### 2.3 MCP 协议通信方式

MCP 支持 3 种通信方式：

| 方式 | 适用场景 | 优点 | 缺点 |
|------|---------|------|------|
| **stdio** | 本地进程间通信（如 Claude Desktop 调用本地 MCP Server） | 简单、低延迟 | 仅限本地，不支持远程调用 |
| **SSE** | 远程流式推送（单向） | 支持流式、实时推送 | 仅单向通信，需要额外通道 |
| **HTTP + JSON-RPC** | 远程双向通信（本阶段推荐） | 支持远程、与 Spring Boot 融合好、支持 Docker 部署 | 不支持 Claude Desktop 的 stdio 模式 |

**本阶段选择：HTTP + JSON-RPC**

理由：
- 目标用户是"其他 AI 应用/框架"，需要远程 HTTP 调用
- 与现有 Spring Boot 架构融合最好
- 支持 Docker Compose 部署
- 协议简单，易于自行实现

### 2.4 MCP 工具规范

MCP Tool 的标准格式：

```json
{
  "name": "database_query",
  "description": "通过自然语言查询业务数据库",
  "inputSchema": {
    "type": "object",
    "properties": {
      "sql": {
        "type": "string",
        "description": "SQL 查询语句（仅支持 SELECT）"
      }
    },
    "required": ["sql"]
  }
}
```

**工具调用请求:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "database_query",
    "arguments": {
      "sql": "SELECT * FROM sys_user LIMIT 10"
    }
  }
}
```

**工具调用响应:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "查询结果：..."
      }
    ]
  }
}
```

---

## 3. 当前系统分析

### 3.1 现有 AgentTool 系统架构

当前项目已有内部的 Agent 工具系统：

```
┌─────────────────────────────────────────┐
│   Spring AI LLM (通义千问)               │
└─────────────────┬───────────────────────┘
                  │ ToolCallbackProvider
┌─────────────────▼───────────────────────┐
│   AgentToolRegistry                      │
│   - 注册所有 AgentTool                   │
│   - 提供工具列表                         │
│   - 执行工具调用                         │
└─────────────────┬───────────────────────┘
                  │ AgentTool 接口
┌─────────────────▼───────────────────────┐
│   AgentTool 实现                         │
│   ├── DatabaseQueryTool                  │
│   ├── CodeSearchTool                     │
│   ├── ApiDocTool                         │
│   └── KnowledgeBaseTool                  │
└─────────────────────────────────────────┘
```

### 3.2 AgentTool 接口定义

```java
public interface AgentTool {
    String getName();                          // 工具名称
    String getDescription();                   // 工具描述
    Map<String, Object> getParameterSchema();  // 参数 Schema（JSON Schema 格式）
    String execute(Map<String, Object> params); // 执行工具
}
```

### 3.3 现有 4 个工具详解

| 工具名称 | 功能 | 参数 | 数据来源 |
|---------|------|------|---------|
| `database_query` | 数据库查询 | `sql` (SELECT 语句) | PostgreSQL |
| `code_search` | 代码搜索 | `keyword`, `fileExtension` | 本地文件系统（./src） |
| `api_doc` | API 文档生成 | `filter` (可选) | Spring RequestMappingHandlerMapping |
| `searchKnowledgeBase` | 知识库搜索 | `question`, `topK` | PGVector 向量库 |

### 3.4 工具实现方式说明

**当前项目存在两种工具实现方式：**

**方式 1：纯 `@Tool` 注解（`KnowledgeBaseTool`）**
- 不实现 `AgentTool` 接口
- 直接在方法上使用 `@Tool` 注解
- Spring AI 自动扫描并注册
- 代码简洁，类型安全

**方式 2：`AgentTool` 接口 + `@Tool` 双支持（其他 3 个工具）**
- 实现 `AgentTool` 接口（`getName()`, `getDescription()`, `getParameterSchema()`, `execute()`）
- 同时保留 `@Tool` 注解方法
- 既支持 Spring AI Agent，也支持 `AgentToolRegistry` 手动调用
- 代码稍冗余，但灵活性更高

**现状评估：** 两种方式都能正常工作，MCP 适配层可以兼容两种方式。无需提前重构，在 MCP 实现过程中根据实际需要决定是否统一。

**阶段 2 决策：** 在 MCP Server 实现时，根据实际适配复杂度决定是否需要统一工具实现方式。初步评估：MCP 适配层可以兼容两种方式，无需专门重构。

### 3.5 现有工具注册机制

通过 Spring AI 的 `ToolCallbackProvider` 自动注册：

```java
@Bean
public ToolCallbackProvider toolCallbackProvider(
        List<Object> toolBeans,
        ToolCallingManager toolCallingManager) {
    return ToolCallbackProvider.from(toolBeans, toolCallingManager);
}
```

所有带 `@Tool` 注解的方法自动被发现并注册给 LLM 使用。

---

## 4. 差距分析

### 4.1 协议层面差距

| 维度 | 当前系统 | MCP 标准 | 差距 |
|------|---------|---------|------|
| **通信协议** | Spring AI 内部调用（无网络协议） | JSON-RPC 2.0 over HTTP/SSE/stdio | ❌ 需要新增 HTTP + JSON-RPC 适配层 |
| **工具描述** | `AgentTool` 接口（Java 对象） | JSON Schema 格式的工具描述 | ⚠️ 格式接近，需要 JSON 序列化 |
| **消息格式** | Java Map 传参 | JSON-RPC 标准格式（`jsonrpc`, `method`, `params`, `id`） | ❌ 需要协议转换 |
| **错误处理** | Java Exception | JSON-RPC 标准错误格式（`code`, `message`, `data`） | ❌ 需要适配 |

**结论:** 协议层面差距较大，需要新增 MCP 协议适配层。

### 4.2 技术实现层面差距

| 维度 | 当前系统 | MCP 标准 | 差距 |
|------|---------|---------|------|
| **依赖库** | Spring AI 1.0 + Spring Boot 3.4 | 无官方 Java MCP SDK | ❌ 需要自行实现或使用第三方库 |
| **通信框架** | Spring MVC REST | JSON-RPC over HTTP | ⚠️ 需要引入 JSON-RPC 框架或自行实现 |
| **工具注册** | Spring 容器自动注入 | MCP Server 手动注册工具列表 | ⚠️ 需要适配层转换 |

**Java MCP 生态调研:**

| 方案 | 成熟度 | 优点 | 缺点 | 推荐度 |
|------|-------|------|------|-------|
| **官方 MCP SDK (Java)** | ❌ 不存在 | - | - | ❌ 不可用 |
| **第三方 Java MCP 库** | ⚠️ 早期阶段 | 可能有现成实现 | 不成熟、文档少、可能不维护 | ⚠️ 不推荐 |
| **自行实现（基于 Spring Boot + JSON-RPC）** | ✅ 可控 | 完全控制、与现有系统融合好 | 需要自己实现协议细节 | ✅ **推荐** |

**结论:** 推荐自行实现 MCP 协议层，基于 Spring Boot + JSON-RPC。

### 4.3 工具适配层面差距

| 工具 | 当前接口 | MCP 标准接口 | 适配工作量 |
|------|---------|-------------|-----------|
| `DatabaseQueryTool` | `execute(Map<String, Object> params)` | JSON-RPC `tools/call` | ✅ 小（只需协议转换） |
| `CodeSearchTool` | 同上 | 同上 | ✅ 小 |
| `ApiDocTool` | 同上 | 同上 | ✅ 小 |
| `KnowledgeBaseTool` | 同上 | 同上 | ✅ 小 |

**结论:** 现有工具无需修改，只需新增 MCP 协议适配层。

### 4.4 架构设计层面差距

| 维度 | 当前系统 | MCP 需求 | 差距 |
|------|---------|---------|------|
| **模块边界** | `company-rag-agent` 模块 | 需要新增 `company-rag-mcp` 模块 | ⚠️ 需要新增模块 |
| **通信方式** | 内部方法调用 | 远程 HTTP 调用 | ❌ 需要新增 HTTP 端点 |
| **租户隔离** | 通过 `X-Tenant-Id` 请求头 | MCP 协议中需要传递租户信息 | ⚠️ 需要在 MCP 协议中扩展租户字段 |

**结论:** 需要新增 MCP 模块，并考虑多租户支持。

### 4.5 安全与权限层面差距

| 维度 | 当前系统 | MCP 需求 | 差距 |
|------|---------|---------|------|
| **认证方式** | JWT Token | MCP 协议无标准认证机制 | ❌ 需要自定义认证扩展 |
| **授权机制** | Spring Security + `@PreAuthorize` | 需要与 MCP 协议集成 | ⚠️ 需要在适配层集成 |
| **租户隔离** | `X-Tenant-Id` 请求头 + RLS | 需要在 MCP 请求中传递租户 ID | ⚠️ 需要在协议中扩展 |

**结论:** 需要在 MCP 协议中扩展认证和租户字段，与现有安全机制集成。

---

## 5. 技术选型

### 5.1 MCP 通信方式

**选择：HTTP + JSON-RPC**

理由已在 3.1 节说明。

### 5.2 JSON-RPC 实现方式

**选择：自行实现（基于 Spring Boot）**

理由：
- Java 生态没有成熟的 MCP SDK
- 自行实现可以完全控制协议细节
- 与现有 Spring Boot 架构融合最好
- MCP 协议相对简单（主要是 JSON-RPC 2.0）

**实现方案:**
- 使用 Spring Boot `@RestController` 处理 HTTP 请求
- 自行解析 JSON-RPC 协议（`jsonrpc`, `method`, `params`, `id`）
- 调用现有 `AgentToolRegistry` 执行工具
- 返回 JSON-RPC 标准响应格式

### 5.3 架构分层（推荐方案）

```
┌─────────────────────────────────────────┐
│   MCP Client (外部 AI 应用)              │
│   - 发现工具 (tools/list)                │
│   - 调用工具 (tools/call)                │
└─────────────────┬───────────────────────┘
                  │ HTTP + JSON-RPC
                  │ Content-Type: application/json
                  │ Authorization: Bearer <token>
                  │ X-Tenant-Id: <tenant-id>
┌─────────────────▼───────────────────────┐
│   MCP Server 适配层 (新增模块)           │
│   company-rag-mcp/                       │
│   ├── McpController                      │
│   │   ├── POST /mcp                      │
│   │   └── GET  /mcp/tools                │
│   ├── JsonRpcHandler                     │
│   │   ├── 解析 JSON-RPC 请求              │
│   │   ├── 验证协议格式                   │
│   │   └── 构建 JSON-RPC 响应              │
│   ├── McpToolAdapter                     │
│   │   ├── tools/list → AgentToolRegistry │
│   │   └── tools/call → AgentTool.execute │
│   └── McpSecurityFilter                  │
│       ├── JWT Token 验证                  │
│       └── 租户 ID 提取                     │
└─────────────────┬───────────────────────┘
                  │ 调用现有接口（无需修改）
┌─────────────────▼───────────────────────┐
│   现有 AgentTool 系统                     │
│   company-rag-agent/                     │
│   └── AgentToolRegistry                  │
│       ├── DatabaseQueryTool              │
│       ├── CodeSearchTool                 │
│       ├── ApiDocTool                     │
│       └── KnowledgeBaseTool              │
└─────────────────────────────────────────┘
```

### 5.4 多租户支持

**方案：在 HTTP 请求头中传递租户信息**

```http
POST /mcp
Content-Type: application/json
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
X-Tenant-Id: 1

{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "database_query",
    "arguments": {
      "sql": "SELECT * FROM sys_user LIMIT 10"
    }
  }
}
```

**McpSecurityFilter 职责:**
1. 验证 JWT Token 有效性
2. 提取 `X-Tenant-Id` 请求头
3. 验证租户 ID 是否在用户的 `tenantIds` 列表中
4. 将租户 ID 设置到 `TenantContext`（现有租户上下文）

---

## 6. 实施路线图

### 6.1 阶段 2：MCP Server 实现

**目标:** 将现有 Agent 工具暴露为标准 MCP Server，支持外部 AI 应用调用。

**主要任务:**
1. 创建 `company-rag-mcp` 模块
2. 实现 `McpController`（HTTP 端点）
3. 实现 `JsonRpcHandler`（协议解析）
4. 实现 `McpToolAdapter`（协议→AgentTool 转换）
5. 实现 `McpSecurityFilter`（JWT + 租户验证）
6. 编写单元测试和集成测试
7. Docker Compose 部署配置

**预计工作量:** 3-5 天

**验收标准:**
- [ ] 外部 AI 应用可以通过 HTTP + JSON-RPC 调用工具
- [ ] 支持 `tools/list` 和 `tools/call` 方法
- [ ] JWT 认证和租户隔离正常工作
- [ ] 现有 Agent 系统继续正常工作（不受影响）

### 6.2 阶段 3：MCP Client 集成

**目标:** 让当前项目的 Agent 能够调用外部 MCP Server 的工具。

**主要任务:**
1. 在 `company-rag-agent` 模块新增 `McpClient` 服务
2. 实现外部 MCP Server 工具发现
3. 实现外部工具调用（JSON-RPC over HTTP）
4. 将外部工具集成到现有 Agent 工具系统
5. 配置外部 MCP Server 连接信息
6. 编写测试

**预计工作量:** 3-5 天

**验收标准:**
- [ ] 可以配置并连接外部 MCP Server
- [ ] 可以发现外部 MCP Server 的工具
- [ ] LLM 可以调用外部工具完成任务
- [ ] 错误处理和超时机制正常

---

## 7. 风险与注意事项

### 7.1 技术风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| **JSON-RPC 协议细节复杂** | 实现工作量大 | MCP 协议相对简单，只需支持 `tools/list` 和 `tools/call` 两个方法 |
| **Java MCP 生态不成熟** | 无参考实现 | 自行实现，参考 TypeScript/Python 的 MCP 实现 |
| **多租户与 MCP 协议集成复杂** | 安全漏洞风险 | 在 HTTP 请求头中传递租户信息，与现有安全机制一致 |

### 7.2 业务风险

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| **外部 AI 应用调用安全性** | 数据泄露风险 | JWT 认证 + 租户隔离 + 工具级别权限控制 |
| **工具调用性能** | 响应延迟 | 异步调用 + 超时控制 + 熔断保护 |

### 7.3 安全措施

- **认证:** 所有 MCP 请求必须携带 JWT Token
- **授权:** 验证租户 ID 是否在用户的 `tenantIds` 列表中
- **审计:** 记录所有 MCP 工具调用日志（操作人、工具名、参数、时间戳）
- **限流:** 每租户 MCP 调用速率限制（使用现有 Redisson 限流）

---

## 8. 待办事项

### 8.1 阶段 1（现状评估）

- [x] 编写现状评估设计文档
- [ ] 用户评审设计文档
- [ ] 根据评审意见修改（如有）
- [ ] 提交 git commit

### 8.2 阶段 2（MCP Server 实现）

- [ ] 编写 MCP Server 实现设计文档
- [ ] 创建 `company-rag-mcp` 模块
- [ ] 实现 MCP 协议适配层
- [ ] 编写测试
- [ ] 部署验证

### 8.3 阶段 3（MCP Client 集成）

- [ ] 编写 MCP Client 集成设计文档
- [ ] 实现 `McpClient` 服务
- [ ] 集成外部工具到 Agent 系统
- [ ] 编写测试
- [ ] 部署验证

---

## 9. 附录

### 9.1 相关文件

- `company-rag-agent/src/main/java/com/company/rag/agent/tool/AgentTool.java`
- `company-rag-agent/src/main/java/com/company/rag/agent/tool/AgentToolRegistry.java`
- `company-rag-agent/src/main/java/com/company/rag/agent/tool/DatabaseQueryTool.java`
- `company-rag-agent/src/main/java/com/company/rag/agent/tool/CodeSearchTool.java`
- `company-rag-agent/src/main/java/com/company/rag/agent/tool/ApiDocTool.java`
- `company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java`

### 9.2 参考资料

- [MCP 官方文档](https://modelcontextprotocol.io/)
- [JSON-RPC 2.0 规范](https://www.jsonrpc.org/specification)
- [Spring Boot 文档](https://spring.io/projects/spring-boot)
- [Spring AI 文档](https://spring.io/projects/spring-ai)

---

## 10. 版本历史

| 版本 | 日期 | 作者 | 变更说明 |
|------|------|------|---------|
| v1.0 | 2026-08-16 | CompanyRag Team | 初始版本 |
