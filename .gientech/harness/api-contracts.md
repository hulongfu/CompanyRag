# API 契约 — CompanyRag

## 统一规范

- **基础路径**：`http://localhost:8080`
- **统一响应格式**：`R<T>` = `{ "code": int, "message": string, "data": T }`
- **认证方式**：请求头 `X-Tenant-Id` 传递租户 ID
- **内容类型**：`application/json`（文件上传使用 `multipart/form-data`）
- **分页**：使用 MyBatis-Plus `Page` 对象

## 健康检查

### GET /actuator/health
- **说明**：应用健康状态
- **响应**：`{ "status": "UP", "components": { "db": {...}, "redis": {...}, "diskSpace": {...} } }`

### GET /actuator/prometheus
- **说明**：Prometheus 指标端点

### GET /actuator/info
- **说明**：应用信息

## RAG 检索

### POST /api/rag/search
- **说明**：RAG 检索（非流式）
- **请求头**：`X-Tenant-Id: {tenantId}`
- **请求体**：
  ```json
  {
    "query": "搜索问题",
    "tenantId": 1,
    "topK": 10,
    "rerankTopK": 5,
    "enableRerank": true
  }
  ```
- **响应**：`R<List<SearchResult>>`

### POST /api/rag/stream
- **说明**：RAG 流式回答（SSE）
- **请求头**：`X-Tenant-Id: {tenantId}`
- **请求体**：
  ```json
  {
    "query": "搜索问题",
    "tenantId": 1
  }
  ```
- **响应**：SSE 流（`text/event-stream`）

### POST /api/rag/retrieve
- **说明**：仅检索文档块，不生成回答
- **请求头**：`X-Tenant-Id: {tenantId}`
- **请求体**：
  ```json
  {
    "query": "搜索关键词",
    "tenantId": 1,
    "topK": 10
  }
  ```
- **响应**：`R<List<DocumentChunk>>`

## 文档管理

### POST /api/document/upload
- **说明**：上传并解析文档
- **请求头**：`X-Tenant-Id: {tenantId}`
- **请求体**：`multipart/form-data`，字段 `file`（支持 PDF/DOCX/TXT/MD/HTML，最大 50MB）
- **响应**：`R<DocumentInfo>`

### GET /api/document/list
- **说明**：文档列表（分页）
- **请求头**：`X-Tenant-Id: {tenantId}`
- **参数**：`page`、`size`
- **响应**：`R<Page<DocumentInfo>>`

### DELETE /api/document/{id}
- **说明**：删除文档
- **请求头**：`X-Tenant-Id: {tenantId}`
- **响应**：`R<Void>`

## Agent

### POST /api/agent/chat
- **说明**：Agent 对话
- **请求体**：
  ```json
  {
    "message": "用户消息",
    "context": "上下文信息"
  }
  ```
- **响应**：`R<AgentResponse>`

## 租户管理

### GET /api/tenant/list
- **说明**：租户列表
- **响应**：`R<List<TenantInfo>>`

### POST /api/tenant/create
- **说明**：创建租户
- **请求体**：
  ```json
  {
    "tenantName": "租户名称",
    "tenantCode": "租户编码"
  }
  ```
- **响应**：`R<TenantInfo>`

## 页面路由

### GET /
- **说明**：知识库首页

### GET /login
- **说明**：登录页

### GET /admin
- **说明**：管理后台

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