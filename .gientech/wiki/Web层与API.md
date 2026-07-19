# Web 层与 API

## 统一响应格式

所有 API 返回 `R<T>` 统一格式：

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

## 统一规范

- **基础路径**：`http://localhost:8080`
- **认证方式**：请求头 `X-Tenant-Id` 传递租户 ID
- **内容类型**：`application/json`（文件上传使用 `multipart/form-data`）
- **分页**：使用 MyBatis-Plus `Page` 对象

## RAG 检索 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/rag/search | RAG 检索（非流式） |
| POST | /api/rag/stream | RAG 流式回答（SSE） |
| POST | /api/rag/retrieve | 仅检索文档块，不生成回答 |

### 检索请求体

```json
{
  "query": "搜索问题",
  "tenantId": 1,
  "topK": 10,
  "rerankTopK": 5,
  "enableRerank": true
}
```

## 文档管理 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/document/upload | 上传并解析文档 |
| GET | /api/document/list | 文档列表 |
| DELETE | /api/document/{id} | 删除文档 |

文件上传支持 PDF/DOCX/TXT/MD/HTML，最大 50MB。

## Agent API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/agent/chat | Agent 智能对话 |
| POST | /api/agent/query-db | 直接查询数据库 |
| POST | /api/agent/search-code | 直接搜索代码 |
| GET | /api/agent/api-doc | 获取 API 文档 |

## 租户管理 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/tenant/list | 租户列表 |
| POST | /api/tenant/create | 创建租户 |

## 健康检查 API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /actuator/health | 应用健康状态 |
| GET | /actuator/prometheus | Prometheus 指标端点 |
| GET | /actuator/info | 应用信息 |

## 页面路由

| 路径 | 说明 |
|------|------|
| / | 知识库首页 |
| /login | 登录页 |
| /admin | 管理后台 |

SPA 回退控制器（`SpaFallbackController`）处理前端路由，确保前端 SPA 应用正常工作。

## 错误码

| code | 含义 |
|------|------|
| 200 | 成功 |
| 400 | 参数错误 |
| 401 | 未授权/租户无效 |
| 404 | 资源不存在 |
| 429 | 请求过于频繁（限流） |
| 500 | 系统内部错误 |
| 503 | 服务熔断（LLM 不可用） |

## 全局异常处理

`GlobalExceptionHandler` 统一捕获异常并转换为 `R<T>` 响应，防止未捕获异常泄漏到前端。
