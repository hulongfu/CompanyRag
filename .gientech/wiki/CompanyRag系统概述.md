# CompanyRag 系统概述

## 项目简介

CompanyRag 是一个企业级知识库检索增强生成（RAG）系统，基于 Spring Boot 3.4 + Spring AI 1.0 + PGVector + 通义千问构建，支持文档解析、智能切分、向量化存储、混合检索、重排序与流式回答等完整 RAG 链路。

## 系统架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Client (Browser)                           │
│              Vue3 + Element Plus (内嵌于Spring Boot)                │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ HTTP / SSE
┌──────────────────────────────▼──────────────────────────────────────┐
│                    Nginx (可选反向代理)                               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────────────┐
│                    CompanyRag Application :8080                      │
│                                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │
│  │ Web 层   │  │ RAG 核心 │  │ Agent   │  │ 可观测性          │   │
│  │ REST API │  │ 混合检索 │  │ MCP工具 │  │ Prometheus +      │   │
│  │ 页面路由 │  │ Rerank   │  │ 数据库  │  │ Micrometer        │   │
│  │          │  │ 流式回答 │  │ 代码检索│  │                   │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────────────┘   │
│                                                                     │
│  ┌──────────┐  ┌──────────┐  ┌────────────────────────────────┐    │
│  │ 多租户   │  │ 文档解析  │  │ 熔断限流                       │    │
│  │ Schema隔离│  │ Tika    │  │ Resilience4j + Redisson缓存    │    │
│  │ 行级安全  │  │ 语义切分 │  │                               │    │
│  └──────────┘  └──────────┘  └────────────────────────────────┘    │
└──────────────┬──────────────────────┬───────────────────────────────┘
               │                      │
               ▼                      ▼
┌─────────────────────────┐  ┌─────────────────────────┐
│     PostgreSQL +        │  │        Redis            │
│     PGVector            │  │   (缓存 / 限流 / 会话)   │
│   (业务数据 / 向量数据)   │  │                         │
└─────────────────────────┘  └─────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────────────────┐
│              LLM / Embedding（OpenAI 兼容，供应商可插拔）              │
│   Chat 默认通义千问 qwen-max ｜ Embedding 默认硅基流动（可独立配置）   │
└──────────────────────────────────────────────────────────────────────┘
```

## 核心特性

### 多租户架构
- **Schema 隔离**：每个租户独立 Schema，数据物理隔离
- **行级安全**：MyBatis-Plus 租户拦截器自动追加 `tenant_id` 条件
- **权限控制**：支持 admin / user / viewer 三种角色

### RAG 全链路
1. **文档解析**：Apache Tika 自动识别 PDF/DOCX/TXT/MD/HTML
2. **语义切分**：三种切分策略可选（语义切分、滑动窗口、固定大小）
3. **向量化**：OpenAI 兼容 Embedding 模型（默认硅基流动）→ PGVector(HNSW 索引)
4. **混合检索**：向量检索 + 关键词检索加权融合
5. **重排序**：Cross-Encoder Rerank 提升 Top-K 准确率
6. **流式回答**：SSE 流式输出

### Agent 能力
- **数据库查询**：通过自然语言查询业务数据
- **代码检索**：在项目源码中搜索代码片段
- **API 文档生成**：动态扫描 Spring 端点生成文档

### 可观测性
- Prometheus 指标埋点：请求数/延迟/召回率/Token 消耗
- Grafana 可视化面板
- Actuator 健康检查

### 工程保障
- **熔断**：Resilience4j CircuitBreaker 保护 LLM 调用
- **限流**：每租户速率限制
- **超时控制**：LLM 调用超时 30 秒
- **两级缓存**：Redis + 热点检测

### 会话历史
- 多轮对话会话管理（`rag_session_meta` 会话元信息 + `rag_session` 对话明细，父子结构）
- 会话列表查询（分页 + 关键词/标签搜索）、详情查看、创建 / 删除 / 更新
- 混合保存策略：首次实时落库，后续异步批量更新；多租户 RLS 行级安全

## 模块结构

| 模块 | 职责 |
|------|------|
| company-rag-common | 常量/异常/工具/统一响应 |
| company-rag-tenant | 多租户(Schema 隔离 + 行级安全) |
| company-rag-document | 文档解析(Tika) + 三种切分策略 |
| company-rag-rag | 混合检索/Rerank/缓存/Prompt/指标 |
| company-rag-agent | MCP 工具编排 |
| company-rag-web | REST Controller + 前端页面 |
| company-rag-bootstrap | 启动入口 + 全局配置 |

## 技术栈

| 组件 | 技术选型 |
|------|---------|
| 框架 | Spring Boot 3.4.4 + Spring AI 1.0.4 |
| 数据库 | PostgreSQL 16 + PGVector |
| 缓存 | Redis (Redisson 3.40.2) |
| ORM | MyBatis-Plus 3.5.9 |
| AI 模型 | Chat: 通义千问 qwen-max / Embedding: 硅基流动 |
| 文档解析 | Apache Tika 3.1.0 |
| 熔断限流 | Resilience4j 2.2.0 |
| 可观测性 | Micrometer + Prometheus + Grafana |
| 前端 | Vue3 + Element Plus (CDN 嵌入) |
| 部署 | Docker Compose |

## 快速开始

### 前置条件
- JDK 17+
- Maven 3.6+
- Docker & Docker Compose
- 模型 API Key（Chat 默认通义千问 DashScope，Embedding 默认硅基流动 SiliconFlow）

### 启动步骤

```bash
# 1. 启动基础设施
docker compose up -d postgres redis

# 2. 配置环境变量
export DASHSCOPE_API_KEY=sk-your-api-key
export SILICONFLOW_API_KEY=sk-your-siliconflow-key

# 3. 编译运行
mvn clean package -DskipTests
java -jar company-rag-bootstrap/target/company-rag-bootstrap-1.0.0-SNAPSHOT.jar

# 4. 访问 http://localhost:8080
```

## 数据库说明

### 数据库初始化

```bash
# 方式一：Docker Compose 自动初始化
docker compose up -d postgres

# 方式二：手动导入
psql -h localhost -U postgres -d company_rag -f sql/init.sql
```

### 表结构概览

| 表名 | 说明 | 是否公共表 |
|------|------|-----------|
| sys_tenant | 租户信息 | 是 |
| sys_user | 用户信息 | 是(tenant_id 隔离) |
| rag_document | 文档元数据 | 是(tenant_id 隔离) |
| doc_chunk | 文档切分块 | 是(tenant_id 隔离) |
| vector_store | 向量存储(PGVector) | 是(metadata->>'tenant_id' 过滤) |
| rag_session_meta | 会话元信息 | 是(tenant_id 隔离) |
| rag_session | 对话历史明细 | 是(tenant_id 隔离) |

### PGVector 说明
- 向量维度: 1024（OpenAI 兼容 Embedding 模型，默认硅基流动 text-embedding）
- 索引类型: HNSW (余弦距离)
- 距离算法: COSINE_DISTANCE
