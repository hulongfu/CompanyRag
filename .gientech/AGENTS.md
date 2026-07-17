# CompanyRag — 企业知识库 RAG 系统

## 项目描述
基于 Spring Boot 3.4 + Spring AI 1.0 + PGVector + 通义千问 的企业级检索增强生成系统，支持文档解析、智能切分、向量化存储、混合检索、重排序与流式回答等完整 RAG 链路。

## 技术栈
- Java 17 + Spring Boot 3.4.4 + Maven 多模块
- Spring AI 1.0.4（DashScope 通义千问）
- PostgreSQL 16 + PGVector（HNSW 索引，1024 维，余弦距离）
- MyBatis-Plus 3.5.9 + Redisson 3.40.2
- Resilience4j 2.2.0（熔断限流）
- Apache Tika 3.1.0（文档解析）
- Micrometer + Prometheus + Grafana（可观测性）
- Docker Compose 部署

## 模块结构
```
company-rag-common      — 常量/异常/工具/统一响应
company-rag-tenant      — 多租户(Schema隔离+行级安全)
company-rag-document    — 文档解析(Tika) + 三种切分策略
company-rag-rag         — 混合检索/Rerank/缓存/Prompt/指标
company-rag-agent       — MCP工具编排
company-rag-web         — REST Controller + 前端页面
company-rag-bootstrap   — 启动入口 + 全局配置
```

## 项目规则
请参考以下 harness 文件：

### 边界定义
`.gientech/harness/boundaries.md` — 模块职责、外部依赖边界、数据流、事务边界

### 编码规范
`.gientech/harness/conventions.md` — Java 编码风格、异常处理、日志规范、安全规范、测试规范

### API 契约
`.gientech/harness/api-contracts.md` — 所有 REST API 定义、请求/响应格式、错误码

### 不可违反规则
`.gientech/harness/iron-rules.md` — 10 条硬性规则（依赖方向、租户隔离、熔断保护、统一响应、敏感信息、向量维度、跨层调用、文件大小、日志安全、可观测性）

### 设计知识库
`.gientech/docs/` — 设计哲学、包结构设计、实践路线图、验证协议

## 关键约束
- 所有 API 返回 `R<T>` 统一格式
- 多租户通过 `X-Tenant-Id` 请求头传递
- 外部 LLM 调用必须配置熔断保护
- 敏感信息通过环境变量注入
- 向量维度 1024，距离算法 COSINE_DISTANCE，索引 HNSW