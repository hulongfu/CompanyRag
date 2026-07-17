# 模块边界定义 — CompanyRag

## 模块职责边界

### company-rag-common
- **职责**：常量定义、异常体系、统一响应模型、公共配置
- **不允许**：依赖任何其他 company-rag 模块、包含业务逻辑
- **关键产出**：`R<T>`、`BizException`、`GlobalExceptionHandler`、`RagConstant`

### company-rag-tenant
- **职责**：多租户上下文管理、Schema 隔离拦截器、权限控制
- **依赖**：common
- **不允许**：直接访问 Document/RAG/Agent 业务逻辑
- **关键产出**：租户上下文 Holder、MyBatis-Plus 租户拦截器

### company-rag-document
- **职责**：文档解析（Tika）、三种切分策略（语义/滑动窗口/固定大小）
- **依赖**：common
- **不允许**：调用 RAG 检索逻辑、直接调用 AI 模型
- **关键产出**：`DocumentParseService`、`SplitStrategy` 枚举

### company-rag-rag
- **职责**：混合检索（向量+关键词）、Cross-Encoder Rerank、两级缓存、Prompt 管理、可观测性指标
- **依赖**：document + tenant + common
- **不允许**：依赖 agent 模块、直接操作 Web 层对象
- **关键产出**：`RagSearchService`、`RagCacheManager`、`RerankService`、`RagMetrics`

### company-rag-agent
- **职责**：MCP 工具编排、数据库 NL 查询、代码检索、API 文档生成
- **依赖**：rag + common
- **不允许**：直接暴露 HTTP 接口、依赖 web 模块
- **关键产出**：`DatabaseQueryTool`、`CodeSearchTool`、`ApiDocTool`、`AgentToolRegistry`

### company-rag-web
- **职责**：REST API 控制器、Thymeleaf 页面路由
- **依赖**：rag + agent + document + common
- **不允许**：包含业务逻辑（仅做参数校验和路由转发）
- **关键产出**：5 个 Controller 类

### company-rag-bootstrap
- **职责**：全局配置、Bean 注册、应用入口
- **依赖**：所有模块
- **不允许**：包含业务代码（仅配置和启动逻辑）

## 外部依赖边界

| 外部系统 | 访问方式 | 配置位置 | 熔断保护 |
|---------|---------|---------|---------|
| PostgreSQL + PGVector | Spring Data + MyBatis-Plus | application.yml datasource | 连接池 Hikari |
| Redis | Redisson Client | application.yml redis | Redisson 自带重试 |
| 通义千问 DashScope | Spring AI OpenAI 兼容模式 | application.yml ai.openai | Resilience4j CircuitBreaker |
| Prometheus | Micrometer + Actuator | application.yml management | 无（监控系统） |

## 数据流边界

```
用户请求 → Controller (参数校验) → Service (业务编排) → 
  ├─ 缓存命中 → 直接返回
  └─ 缓存未命中 → 
       ├─ 向量检索 (PGVector HNSW)
       ├─ 关键词检索 (pg_trgm)
       ├─ 融合排序
       ├─ Cross-Encoder Rerank
       ├─ LLM 生成 (通义千问)
       └─ 写入缓存 + 记录指标
```

## 事务边界

- 每个 Service 方法为一个事务单元
- 跨模块调用不共享事务
- 缓存操作不在事务内执行
- LLM 调用不在事务内执行