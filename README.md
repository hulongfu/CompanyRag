# CompanyRag - 企业知识库RAG系统

[![Gitee Go CI](https://gitee.com/LongHuDaoChang/CompanyRag/badge/ci.svg)](https://gitee.com/LongHuDaoChang/CompanyRag/ci)

> 企业级知识库检索增强生成(RAG)系统，基于 Spring AI（OpenAI 兼容，模型供应商可插拔）+ PGVector

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

### 📐 多租户架构
- **Schema隔离**：每个租户独立Schema，数据物理隔离
- **行级安全**：MyBatis-Plus 租户拦截器自动追加 `tenant_id` 条件
- **权限控制**：支持 admin / user / viewer 三种角色

### 🔄 RAG全链路
1. **文档解析**：Apache Tika 自动识别 PDF/DOCX/TXT/MD/HTML
2. **语义切分**：三种切分策略可选
3. **向量化**：OpenAI 兼容 Embedding 模型（默认硅基流动）→ PGVector(HNSW索引)
4. **混合检索**：向量检索 + 关键词检索加权融合
5. **重排序**：Cross-Encoder Rerank 提升Top-K准确率（硅基流动 BAAI/bge-reranker-v2-m3 模型）
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

### 💬 会话历史
- 多轮对话会话管理（`rag_session_meta` 会话元信息 + `rag_session` 对话明细，父子结构）
- 会话列表查询（分页 + 关键词/标签搜索）、详情查看、创建 / 删除 / 更新
- 混合保存策略：首次实时落库，后续异步批量更新；多租户 RLS 行级安全
- 实现路径：Superpowers 工作流（设计稿 + 实现计划 + 代码），REST API 见 `/api/session`

## 技术栈

| 组件 | 技术选型 |
|------|---------|
| 框架 | Spring Boot 3.4 + Spring AI 1.0 |
| 数据库 | PostgreSQL 16 + PGVector |
| 缓存 | Redis (Redisson) |
| ORM | MyBatis-Plus 3.5 |
| AI模型 | Chat: 通义千问 qwen-max（默认，OpenAI 兼容）／Embedding: 硅基流动（默认，可独立替换）／Rerank: 硅基流动 BAAI/bge-reranker-v2-m3（默认，零Token成本） |
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
- 模型 API Key（OpenAI 兼容：Chat 默认通义千问 DashScope，Embedding 默认硅基流动 SiliconFlow，二者可独立替换为任意兼容服务）

### 1. 启动基础设施

```bash
# 启动 PostgreSQL (PGVector) + Redis
docker compose up -d postgres redis
```

### 2. 配置环境变量

复制 `.env.example` 为 `.env` 并填入密钥。模型层为 OpenAI 兼容，Chat 与 Embedding 的供应商可独立配置：

```bash
# Windows (cmd)
set DASHSCOPE_API_KEY=sk-your-api-key
set SILICONFLOW_API_KEY=sk-your-siliconflow-key

# Linux/Mac
export DASHSCOPE_API_KEY=sk-your-api-key
export SILICONFLOW_API_KEY=sk-your-siliconflow-key
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
export SILICONFLOW_API_KEY=sk-your-siliconflow-key
docker compose up -d
```

## API文档

### 统一对话（Agent 模式，推荐）
```bash
POST /api/chat
Content-Type: application/json
X-Tenant-Id: 1

{
  "query": "怎么申请测试环境？",
  "sessionId": "your-session-id",
  "tenantId": 1,
  "userId": 1
}
```

系统会通过 Agent 编排，LLM 自动判断是否需要调用工具，并生成最终回答。

### RAG独立检索（旧接口，已废弃）
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

### 会话管理
```bash
# 创建会话
POST /api/session
Content-Type: application/json
X-Tenant-Id: 1
{"title": "新会话"}

# 获取会话列表
GET /api/session/list?page=1&size=20
X-Tenant-Id: 1

# 获取会话详情（聊天记录）
GET /api/session/{sessionId}
X-Tenant-Id: 1

# 删除会话
DELETE /api/session/{sessionId}
X-Tenant-Id: 1

# 更新会话信息
PUT /api/session/{sessionId}
Content-Type: application/json
X-Tenant-Id: 1
{"title": "新标题", "tags": ["tag1"]}
```

## Agent 工具详解

系统内置 4 个 Agent 工具，由 LLM 在对话过程中根据用户问题自主决定是否调用。以下详细介绍每个工具的使用场景和触发示例。

### 1. 🔍 知识库搜索工具（searchKnowledgeBase）

> **核心 RAG 工具，企业知识问答的入口**

- **位置**：`company-rag-rag/.../tools/KnowledgeBaseTool.java`
- **@Tool 名称**：`searchKnowledgeBase`
- **依赖服务**：`RagSearchService`（混合检索 + Cross-Encoder Rerank）
- **数据来源**：已上传至 PostgreSQL PGVector 的文档向量库

#### 使用场景

| 场景 | 示例问题 |
|------|---------|
| 查询 README / 项目说明 | "这个项目的架构是什么样的？" |
| 查询设计文档 | "用户模块的数据库表结构是怎样的？" |
| 查询操作手册 / FAQ | "如何部署这个系统？" |
| 查询流程规范 | "代码提交流程是什么？" |
| 查询技术文档 | "PGVector 的索引类型是什么？" |

#### 触发示例

用户提问后，LLM 判断问题涉及**知识库文档内容**时自动调用：

```
用户：怎么申请测试环境？
→ LLM 调用 searchKnowledgeBase(question="怎么申请测试环境？")
→ RAG 引擎执行混合检索（向量 + 全文检索）+ Rerank 重排序
→ 返回带引用来源的答案给 LLM
→ LLM 生成自然语言回复
```

```
用户：这个系统的技术栈有哪些？
→ LLM 调用 searchKnowledgeBase(question="系统技术栈", topK=5)
→ 返回 README 中技术栈相关片段
```

#### 返回格式

```json
{
  "success": true,
  "answer": "找到以下相关文档片段：\n\n[1] 来源：README.md\n...",
  "citations": [
    {
      "documentName": "README.md",
      "snippet": "系统基于 Spring Boot 3.4...",
      "score": 0.92,
      "chunkIndex": 3
    }
  ]
}
```

#### 触发条件

LLM 判断用户问题涉及**企业内部知识**（文档、手册、规范、说明等）时触发。**不会**搜索源代码文件（.java/.ts/.py 等）。

---

### 2. 🗄️ 数据库查询工具（database_query）

> **业务数据查询入口，通过自然语言查询数据库**

- **位置**：`company-rag-agent/.../tool/DatabaseQueryTool.java`
- **@Tool 名称**：`database_query`
- **安全措施**：仅允许 SELECT、禁止 DDL/DML、自动 LIMIT 100 行、危险关键字检测

#### 使用场景

| 场景 | 示例问题 |
|------|---------|
| 查询用户信息 | "查看用户表结构" 或 "查询所有用户" |
| 查询订单数据 | "最近10个订单有哪些？" |
| 查询产品信息 | "产品表有哪些字段？" |
| 数据统计 | "总共有多少用户？" |

#### 触发示例

```
用户：查看用户表有哪些字段？
→ LLM 调用 database_query(sql="SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = 'sys_user'")
→ 返回用户表的列名、数据类型、是否可空信息
```

```
用户：查询所有启用的租户
→ LLM 调用 database_query(sql="SELECT * FROM sys_tenant WHERE status = 1")
→ 返回租户列表数据
```

#### 安全机制

1. 仅允许 `SELECT` 语句
2. 禁止关键字：`DROP`、`DELETE`、`UPDATE`、`INSERT`、`TRUNCATE`、`ALTER`、`CREATE` 等
3. 自动添加 `LIMIT 100`（如果未指定）
4. 表名校验：仅允许字母、数字、下划线

---

### 3. 📄 API 文档工具（api_doc）

> **动态扫描系统所有 REST 端点，生成 API 文档**

- **位置**：`company-rag-agent/.../tool/ApiDocTool.java`
- **@Tool 名称**：`api_doc`
- **依赖**：Spring `RequestMappingHandlerMapping`（运行时动态扫描）

#### 使用场景

| 场景 | 示例问题 |
|------|---------|
| 查看所有 API | "系统有哪些接口？" |
| 查看特定 API | "上传文档的接口是什么？" |
| 查看 API 详情 | "搜索接口的请求参数有哪些？" |

#### 触发示例

```
用户：系统有哪些 API 接口？
→ LLM 调用 api_doc(filter=null)
→ 返回所有 Spring MVC 端点列表（HTTP 方法 + 路径 + Controller 方法）
```

```
用户：上传文档的接口是什么？
→ LLM 调用 api_doc(filter="upload")
→ 返回包含 "upload" 关键字的所有端点
```

#### 返回格式

```
## API 文档
  [POST] [/api/document/upload] -> DocumentController.upload()
  [POST] [/api/rag/search] -> ChatController.ragSearch()
  [POST] [/api/chat] -> ChatController.chat()
  ...
```

---

### 4. 💻 代码搜索工具（code_search）

> **在项目源码目录中搜索代码片段**

- **位置**：`company-rag-agent/.../tool/CodeSearchTool.java`
- **@Tool 名称**：`code_search`
- **搜索范围**：`${app.code-search.src-base}`（默认 `./src`）

#### 使用场景

| 场景 | 示例问题 |
|------|---------|
| 搜索关键词 | "搜索代码中哪里用到了 RedisTemplate" |
| 按文件类型搜索 | "搜索所有 Controller 中关于 session 的代码" |
| 代码定位 | "RagSessionServiceImpl 在哪个包？" |

#### 触发示例

```
用户：搜索代码中哪里用到了 RedisTemplate
→ LLM 调用 code_search(keyword="RedisTemplate", fileExtension=null)
→ 返回所有包含 "RedisTemplate" 的文件路径和匹配行
```

```
用户：Controller 中关于 session 的代码有哪些？
→ LLM 调用 code_search(keyword="session", fileExtension=".java")
→ 返回所有 .java 文件中包含 "session" 的代码行
```

#### 返回格式

```
./src/main/java/.../RagSessionServiceImpl.java: public class RagSessionServiceImpl implements RagSessionService {
./src/main/java/.../SessionController.java: public class SessionController {
./src/main/java/.../ChatController.java: private final RagSessionService ragSessionService;
...
```

---

### 工具选择决策流程

```
用户提问
    │
    ▼
┌─────────────────────────────┐
│    LLM 分析问题意图          │
└─────────────────────────────┘
    │
    ├── 涉及企业知识/文档内容 ──→ searchKnowledgeBase（RAG 检索）
    │
    ├── 涉及业务数据库数据  ──→ database_query（SQL 查询）
    │
    ├── 涉及系统 API 接口   ──→ api_doc（扫描端点）
    │
    ├── 涉及项目源码        ──→ code_search（代码搜索）
    │
    └── 以上都不涉及        ──→ LLM 直接回答（无需工具）
```

### 工具配置

工具注册配置位于 `company-rag-rag/.../config/AgentToolConfig.java`，通过 Spring AI 的 `ToolCallbackProvider` 自动注册所有带 `@Tool` 注解的方法。

```java
@Bean
public ToolCallbackProvider toolCallbackProvider(
        List<Object> toolBeans,
        ToolCallingManager toolCallingManager) {
    return ToolCallbackProvider.from(toolBeans, toolCallingManager);
}
```

所有工具 Bean 通过 Spring 容器自动注入，无需手动注册。新增工具只需：
1. 创建 `@Component` 类
2. 在方法上添加 `@Tool(name = "...", description = "...")` 注解
3. Spring AI 自动发现并注册

## 性能优化要点

### Token成本优化
1. **语义切分**减少冗余块，提升Token利用率
2. **两级缓存**避免重复计算
3. **动态Top-K**根据query复杂度调整检索数量
4. **Prompt压缩**去除低价值上下文

### 召回率提升
1. **多路混合检索**：向量 + 全文 + 模糊三路召回，动态权重融合
2. **Cross-Encoder Rerank**：精排 Top-K 准确率提升 15-30%
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
| sys_user | 用户信息 | 是(tenant_id隔离) |
| rag_document | 文档元数据 | 是(tenant_id隔离) |
| doc_chunk | 文档切分块 | 是(tenant_id隔离) |
| vector_store | 向量存储(PGVector) | 是(metadata->>'tenant_id'过滤) |
| rag_session_meta | 会话元信息 | 是(tenant_id隔离) |
| rag_session | 对话历史明细 | 是(tenant_id隔离) |

### PGVector 说明

- 向量维度: 1024（OpenAI 兼容 Embedding 模型，默认硅基流动 text-embedding）
- 索引类型: HNSW (余弦距离)
- 距离算法: COSINE_DISTANCE

## License

MIT License
