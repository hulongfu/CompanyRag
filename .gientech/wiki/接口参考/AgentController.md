# AgentController（已废弃）

**本文档中引用的文件**
- [AgentController.java](../../../company-rag-web/src/main/java/com/company/rag/web/controller/AgentController.java)
- [RagAgentService.java](../../../company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java)
- [AgentResult.java](../../../company-rag-agent/src/main/java/com/company/rag/agent/service/AgentResult.java)
- [ChatController.java](../../../company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java)

## 目录
1. [简介](#简介)
2. [废弃说明](#废弃说明)
3. [API 接口列表](#api 接口列表)
4. [数据模型](#数据模型)
5. [迁移指南](#迁移指南)

## 简介

AgentController 是早期的智能对话接口控制器，提供简单的聊天、数据库查询、代码搜索和 API 文档查询功能。该控制器已标记为 **`@Deprecated`**，**请使用 [ChatController](../../../company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java) 替代**。

**技术特性**：
- 基于 Spring Boot 3.4 + Spring AI 1.0.4（DashScope 通义千问）
- RESTful API 设计风格
- 统一响应格式 `R<T>`
- 依赖 RagAgentService 实现 Agent 编排能力

**废弃原因**：
- 功能单一，缺乏统一的请求/响应模型
- 不支持会话管理
- 不支持多租户参数传递
- 缺乏可解释性日志和调试信息
- 已整合至 ChatController 的统一对话接口

## 废弃说明

**AgentController 已标记为 `@Deprecated`**，不建议在新代码中使用。原有功能已迁移至 [ChatController](../../../company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java)，提供更完善的 Agent 编排能力。

```java
/**
 * @deprecated 使用 {@link ChatController} 替代
 */
@Deprecated
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {
    // ... 原有接口方法
}
```

**来源**: [AgentController.java](../../../company-rag-web/src/main/java/com/company/rag/web/controller/AgentController.java)(L10-L17)

## API 接口列表

> **重要提示**：以下接口均已废弃，仅供现有前端兼容使用。新开发请使用 [ChatController 的统一对话接口](./对话接口.md)。

### 1. 聊天接口（废弃）

**端点**: `POST /api/agent/chat`

**请求体**:
```json
{
  "message": "如何配置 PGVector 索引？"
}
```

**响应** (`R<String>`):
```json
{
  "code": 200,
  "message": "success",
  "data": "PGVector 索引配置需要在 PostgreSQL 中启用 vector 扩展..."
}
```

**实现逻辑**：
1. 从请求体获取 `message` 参数
2. 调用 `RagAgentService.process(message)` 处理请求
3. 返回 AgentResult 中的 answer 字段

**来源**: [AgentController.java](../../../company-rag-web/src/main/java/com/company/rag/web/controller/AgentController.java)(L21-L26)

---

### 2. 数据库查询接口（废弃）

**端点**: `POST /api/agent/query-db`

**请求体**:
```json
{
  "sql": "SELECT * FROM users WHERE tenant_id = 1001"
}
```

**响应** (`R<String>`):
```json
{
  "code": 200,
  "message": "success",
  "data": "查询结果：..."
}
```

**实现逻辑**：
1. 从请求体获取 `sql` 参数
2. 调用 `RagAgentService.queryDatabase(sql)` 执行查询
3. 返回查询结果

**来源**: [AgentController.java](../../../company-rag-web/src/main/java/com/company/rag/web/controller/AgentController.java)(L28-L31)

---

### 3. 代码搜索接口（废弃）

**端点**: `POST /api/agent/search-code`

**请求体**:
```json
{
  "keyword": "PGVector 配置",
  "fileExtension": ".java"
}
```

**响应** (`R<String>`):
```json
{
  "code": 200,
  "message": "success",
  "data": "搜索到的代码片段..."
}
```

**实现逻辑**：
1. 从请求体获取 `keyword` 和 `fileExtension` 参数
2. 调用 `RagAgentService.searchCode(keyword, fileExtension)` 执行搜索
3. 返回搜索结果

**来源**: [AgentController.java](../../../company-rag-web/src/main/java/com/company/rag/web/controller/AgentController.java)(L33-L38)

---

### 4. API 文档查询接口（废弃）

**端点**: `GET /api/agent/api-doc`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| filter | String | 否 | 过滤条件（可选） |

**响应** (`R<String>`):
```json
{
  "code": 200,
  "message": "success",
  "data": "API 文档内容..."
}
```

**实现逻辑**：
1. 从请求参数获取 `filter`（可选）
2. 调用 `RagAgentService.getApiDoc(filter)` 获取文档
3. 返回文档内容

**来源**: [AgentController.java](../../../company-rag-web/src/main/java/com/company/rag/web/controller/AgentController.java)(L40-L43)

## 数据模型

### AgentResult（Agent 处理结果）

| 字段 | 类型 | 说明 | 来源 |
|------|------|------|------|
| answer | String | Agent 生成的回答 | L17 |
| toolContext | String | 工具上下文信息（如调用了什么工具） | L22 |

**来源**: [AgentResult.java](../../../company-rag-agent/src/main/java/com/company/rag/agent/service/AgentResult.java)

## 迁移指南

### 从 AgentController 迁移到 ChatController

| 原接口（废弃） | 新接口（推荐） | 说明 |
|---------------|---------------|------|
| `POST /api/agent/chat` | `POST /api/chat` | 统一对话接口，支持更多参数 |
| `POST /api/agent/query-db` | `POST /api/chat` (mode: "agent") | Agent 自动识别数据库查询意图 |
| `POST /api/agent/search-code` | `POST /api/chat` (mode: "agent") | Agent 自动识别代码搜索意图 |
| `GET /api/agent/api-doc` | `POST /api/chat` (mode: "agent") | Agent 自动识别 API 文档查询意图 |

### 迁移示例

**原 AgentController 调用**：
```javascript
// 聊天
fetch('/api/agent/chat', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ message: '你好' })
});

// 数据库查询
fetch('/api/agent/query-db', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ sql: 'SELECT * FROM users' })
});
```

**新 ChatController 调用**：
```javascript
// 统一对话接口（推荐）
fetch('/api/chat', {
  method: 'POST',
  headers: { 
    'Content-Type': 'application/json',
    'X-Tenant-Id': '1001'  // 支持多租户
  },
  body: JSON.stringify({
    query: '你好',
    sessionId: 'sess_abc123',  // 支持会话管理
    includeDebug: false,       // 支持调试信息
    mode: 'agent'              // Agent 模式，自动意图识别
  })
});
```

**新接口优势**：
- 支持多租户隔离（`X-Tenant-Id` 请求头）
- 支持会话管理（`sessionId` 参数）
- 支持调试信息（`includeDebug` 参数）
- 自动意图识别，无需手动区分接口
- 完整的性能指标和来源追溯

**详细说明**: 请参阅 [对话接口文档](./对话接口.md)

---

**文档更新时间**: 2026-08-09  
**状态**: 已废弃（Deprecated）  
**替代方案**: [ChatController 统一对话接口](./对话接口.md)
