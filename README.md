# CompanyRag - 企业知识库RAG系统

> 企业级知识库检索增强生成(RAG)系统，基于 Spring AI + PGVector + 通义千问

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
│                    通义千问 (DashScope)                              │
│        text-embedding-v3 (向量化) + qwen-max (对话/Agent)          │
└──────────────────────────────────────────────────────────────────────┘
```

## 核心特性

### 📐 多租户架构
- **Schema隔离**：每个租户独立Schema，数据物理隔离
- **行级安全**：MyBatis-Plus 租户拦截器自动追加 `tenant_id` 条件
- **权限控制**：支持 admin / user / viewer 三种角色

### 🔄 RAG全链路
1. **文档解析**：Apache Tika 自动识别 PDF/DOCX/TXT/MD/HTML
2. **语义切分**：三种切分策略可选
3. **向量化**：通义千问 text-embedding-v3 → PGVector(HNSW索引)
4. **混合检索**：向量检索 + 关键词检索加权融合
5. **重排序**：Cross-Encoder Rerank 提升Top-K准确率
6. **流式回答**：SSE 流式输出

### 🎯 切分策略对比 (工程亮点)

| 策略 | 原理 | 适用场景 | Token利用率 |
|------|------|---------|------------|
| **语义切分 (RSE风格)** | 按Markdown标题/段落边界递归切分 | 结构化文档(技术文档/手册) | ⭐⭐⭐⭐⭐ |
| **滑动窗口** | 固定大小 + 重叠 + 句边界感知 | 通用文本 | ⭐⭐⭐⭐ |
| **固定大小** | 按字符数切分 | 无结构文本 | ⭐⭐⭐ |

### 🤖 Agent能力
- **数据库查询**：通过自然语言查询业务数据
- **代码检索**：在项目源码中搜索代码片段
- **API文档生成**：动态扫描Spring端点生成文档

### 📊 可观测性
- Prometheus 指标埋点：请求数/延迟/召回率/Token消耗
- Grafana 可视化面板
- Actuator 健康检查

### 🛡️ 工程保障
- **熔断**：Resilience4j CircuitBreaker 保护LLM调用
- **限流**：每租户速率限制
- **超时控制**：LLM调用超时30秒
- **两级缓存**：Redis + 热点检测

## 技术栈

| 组件 | 技术选型 |
|------|---------|
| 框架 | Spring Boot 3.4 + Spring AI 1.0 |
| 数据库 | PostgreSQL 16 + PGVector |
| 缓存 | Redis (Redisson) |
| ORM | MyBatis-Plus 3.5 |
| AI模型 | 通义千问 qwen-max + text-embedding-v3 |
| 文档解析 | Apache Tika |
| 熔断限流 | Resilience4j |
| 可观测性 | Micrometer + Prometheus + Grafana |
| 前端 | Vue3 + Element Plus (CDN嵌入) |
| 部署 | Docker Compose |

## 快速开始

### 前置条件
- JDK 17+
- Maven 3.6+
- Docker & Docker Compose
- 通义千问 API Key ([DashScope](https://dashscope.aliyun.com))

### 1. 启动基础设施

```bash
# 启动 PostgreSQL (PGVector) + Redis
docker compose up -d postgres redis
```

### 2. 配置环境变量

```bash
# Windows (cmd)
set DASHSCOPE_API_KEY=sk-your-api-key

# Linux/Mac
export DASHSCOPE_API_KEY=sk-your-api-key
```

### 3. 编译运行

```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar company-rag-bootstrap/target/company-rag-bootstrap-1.0.0-SNAPSHOT.jar
```

### 4. 访问系统

| 地址 | 说明 |
|------|------|
| http://localhost:8080 | 知识库首页 |
| http://localhost:8080/login | 登录页 |
| http://localhost:8080/admin | 管理后台 |
| http://localhost:9090 | Prometheus |
| http://localhost:3000 | Grafana (admin/admin) |

### 5. Docker Compose 完整部署

```bash
export DASHSCOPE_API_KEY=sk-your-api-key
docker compose up -d
```

## API文档

### RAG检索
```bash
POST /api/rag/search
Content-Type: application/json
X-Tenant-Id: 1

{
  "query": "什么是微服务架构？",
  "tenantId": 1,
  "topK": 10,
  "rerankTopK": 5,
  "enableRerank": true
}
```

### 流式回答
```bash
POST /api/rag/stream
Content-Type: application/json
X-Tenant-Id: 1

{
  "query": "什么是微服务架构？",
  "tenantId": 1
}
```

### 文档上传
```bash
POST /api/document/upload
Content-Type: multipart/form-data
X-Tenant-Id: 1

file: @document.pdf
```

### Agent工具
```bash
POST /api/agent/chat
Content-Type: application/json

{
  "message": "查询用户表结构",
  "context": "当前租户: default"
}
```

## 性能优化要点

### Token成本优化
1. **语义切分**减少冗余块，提升Token利用率
2. **两级缓存**避免重复计算
3. **动态Top-K**根据query复杂度调整检索数量
4. **Prompt压缩**去除低价值上下文

### 召回率提升
1. **混合检索**：向量 + 关键词加权融合
2. **Cross-Encoder Rerank**：精排Top-K准确率提升15-30%
3. **滑动窗口重叠**：减少信息断裂
4. **句边界感知**：切分时保持语义完整性

## 项目结构

```
company-rag/
├── company-rag-common/        # 公共模块(常量/异常/工具)
├── company-rag-tenant/        # 多租户模块(上下文/拦截器/权限)
├── company-rag-document/      # 文档模块(解析/切分策略)
│   └── splitter/
│       ├── FixedSizeSplitter      # 固定大小切分
│       ├── SlidingWindowSplitter  # 滑动窗口切分
│       └── SemanticChunkSplitter  # 语义边界切分(RSE风格)
├── company-rag-rag/           # RAG核心(检索/Rerank/缓存/Prompt)
│   ├── service/               # RAG检索服务 + 熔断限流配置
│   ├── rerank/                # Cross-Encoder重排序
│   ├── cache/                 # 两级缓存管理
│   ├── prompt/                # Prompt模板管理
│   └── observability/         # Prometheus指标埋点
├── company-rag-agent/         # Agent模块(MCP工具)
│   ├── tool/                  # 数据库查询/代码检索/API文档工具
│   └── service/               # Agent编排服务
├── company-rag-web/           # Web层(Controller + 前端页面)
├── company-rag-bootstrap/     # 启动模块(配置/入口)
├── sql/                       # 数据库初始化脚本
├── docker-compose.yml         # Docker编排
├── Dockerfile                 # 多阶段构建
└── prometheus.yml             # 监控配置
```

## License

MIT License
