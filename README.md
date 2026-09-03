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
- **用户 - 租户关联表**：`sys_user` 在 `public` schema，通过 `sys_user_tenant_rel` 关联多租户（多对多）
- **Schema 隔离**：每个租户独立 Schema，数据物理隔离
- **行级安全 (RLS)**：业务表（rag_document/doc_chunk/rag_session/rag_session_meta）使用 RLS 行级安全策略，通过 GUC `app.tenant_id` 控制访问
- **向量存储**：`vector_store` 表仅通过 Schema 隔离（不启用 RLS，由 PgVectorStore 直连 JDBC）
- **权限控制**：支持 admin / user / viewer 三种角色
  - **平台级超级管理员**：系统唯一 admin 账号，管理所有租户（通过 SQL 初始化脚本创建）
  - **普通用户**：由 admin 创建，关联到指定租户（user/viewer 角色）
- **租户切换**：前端管理 `currentTenantId`，API 调用通过 `X-Tenant-Id` 头传递，后端验证 JWT 中的 `tenantIds`
- **安全加固**：使用专用数据库用户 `company_rag_app`（非超级用户），移除 RLS 策略中的 `postgres` 后门
- **自动关联**：admin 创建租户时自动建立与该租户的关联（通过 `sys_user_tenant_rel`）
- **默认租户**：首次部署时自动创建 `tenant_default` 租户，供 admin 首次登录使用

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

### 👤 用户管理（管理员专属）
- **多租户关联**：用户可以关联多个租户（通过 `sys_user_tenant_rel` 表多对多关联）
- **角色权限**：支持 admin（管理员）/ user（普通用户）/ viewer（访客）三种角色
- **CRUD 操作**：仅管理员可访问用户管理界面，支持创建/查询/编辑/删除用户
- **级联删除**：删除用户时自动清理 `sys_user_tenant_rel` 关联数据
- **密码加密**：使用 BCrypt 强哈希存储，支持创建时设置初始密码、编辑时可选修改
- **筛选查询**：支持按角色/租户/状态/用户名模糊搜索
- **实现路径**：Superpowers 工作流，REST API 见 `/api/user`

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

复制 `.env.example` 为 `.env` 并配置以下必需变量：

```bash
# 1. 复制模板
copy .env.example .env    # Windows
cp .env.example .env      # Linux/Mac

# 2. 编辑 .env 文件，配置以下必需变量：

# 【必须配置】JWT Token 密钥（生产环境必须设置强随机密钥）
# 生成方法：openssl rand -base64 32
# 
# 重要说明：
#   - JWT_SECRET 不是登录密码，而是用于签名和验证 JWT Token 的密钥
#   - 登录密码：存储在数据库中，用户注册/修改时设置
#   - JWT_SECRET：服务端持有，登录成功后生成 Token 时签名，每次请求时验证 Token 真伪
#   - 开发环境：可以固定使用一个随机密钥（生成一次后记录到 .env，开发期间不变）
#   - 生产环境：必须使用强随机密钥，定期轮换
#   - 修改 JWT_SECRET 后：之前颁发的 Token 会失效，用户需要重新登录
#   - 安全警告：切勿使用示例密钥（如 your_jwt_secret、this-is-a-secret 等），否则应用拒绝启动
JWT_SECRET=your_jwt_secret_key_here

# 【必须配置】DashScope API Key（通义千问）
DASHSCOPE_API_KEY=sk-your-api-key

# 【可选】SiliconFlow API Key（用于 Embedding 和 Rerank）
SILICONFLOW_API_KEY=sk-your-siliconflow-key

# 【必须配置】数据库密码（生产环境必须设置强密码）
POSTGRES_PASSWORD=your_strong_database_password

# 【必须配置】Grafana 管理员密码（生产环境必须修改）
GRAFANA_ADMIN_PASSWORD=your_grafana_admin_password

# 【可选】代码搜索根目录（默认使用项目根目录）
# CODE_SEARCH_SRC_BASE=/path/to/your/source/code
```

**重要提示**：
- `JWT_SECRET` 未配置或使用默认值会导致应用拒绝启动（安全保护机制）
- 数据库密码、Grafana 密码在生产环境必须使用强密码
- 所有密钥建议使用密码管理器生成和存储

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
| http://localhost:8080 | 知识库首页（需登录） |
| http://localhost:8080/login | 登录页 |
| http://localhost:8080/admin | 管理后台（需 admin 权限） |
| http://localhost:9090 | Prometheus |
| http://localhost:3000 | Grafana (admin/admin) |

### 5. 登录认证

系统使用 JWT（JSON Web Token）无状态认证。所有 API 请求需要在 Header 中携带 Token。

#### JWT 工作原理

```
用户登录 → 验证用户名/密码 → 生成 JWT Token（用 JWT_SECRET 签名）
                          ↓
后续请求 → 携带 Token → 验证签名（用 JWT_SECRET） → 解析用户信息
```

**关键概念：**
- **登录密码**：存储在数据库中，用户注册/修改时设置
- **JWT_SECRET**：服务端持有的密钥，用于：
  - 登录成功后生成 Token 时签名
  - 每次请求时验证 Token 的真伪
- **Token 有效期**：默认 2 小时（7200 秒），过期后需要重新登录或刷新

**重要提示：**
- ⚠️ **JWT_SECRET 不是登录密码**，不要与用户账号密码混淆
- ⚠️ **修改 JWT_SECRET 后**：之前颁发的所有 Token 会失效，用户需要重新登录
- ✅ **开发环境**：可以固定使用一个随机密钥（生成一次后记录到 .env，开发期间保持不变）
- ✅ **生产环境**：必须使用强随机密钥，建议定期轮换（轮换后用户需要重新登录）

#### 5.0 首次部署说明

**Flyway 会自动执行数据库初始化脚本，无需手动执行 SQL！**

首次启动应用时，Flyway 会自动按顺序执行以下迁移脚本：
- `V1__fix_tenant_isolation_security.sql` - 多租户隔离安全修复
- `V2__fix_database_query_tool_cross_tenant_access.sql` - DatabaseQueryTool 跨租户访问修复
- `V3__init_platform_admin.sql` - 系统初始化（创建 admin 账号和默认租户）

**验证初始化完成：**
```bash
# 检查 Flyway 迁移历史
psql -U postgres -d company_rag -c "SELECT version, description, state FROM flyway_schema_history ORDER BY installed_rank;"

# 验证 admin 账号创建成功
psql -U postgres -d company_rag -c "SELECT username, role FROM sys_user WHERE username='admin';"

# 验证默认租户创建成功
psql -U postgres -d company_rag -c "SELECT tenant_code, tenant_name FROM sys_tenant WHERE tenant_code='tenant_default';"
```

**初始化脚本会创建：**
- ✅ 平台级超级管理员账号：`admin` / 密码：`admin123`
- ✅ 默认租户：`tenant_default`（租户名称：默认租户）
- ✅ admin 与默认租户的关联关系
- ✅ 默认租户的 schema 和业务表（vector_store、doc_chunk、rag_document、rag_session、rag_session_meta）

**重要提示：**
- ⚠️ 首次登录后请立即修改 admin 密码
- 📋 默认租户供 admin 首次登录使用，后续可通过租户管理创建其他租户
- 🔒 admin 账号是平台级超级管理员，关联所有创建的租户
- 🔄 Flyway 配置见 `application.yml` 中的 `flyway.*` 配置项

---

#### 5.1 前端登录流程（推荐）

1. **访问登录页**：浏览器打开 `http://localhost:8080/login`
2. **输入凭据**：填写用户名和密码，点击登录
3. **自动存储**：登录成功后，前端自动将以下信息存储到 `localStorage`：
   - `token`：JWT Token（用于 API 认证）
   - `refreshToken`：刷新令牌（Token 过期后刷新）
   - `userId`：用户 ID
   - `tenantIds`：用户可访问的租户 ID 列表（数组）
   - `currentTenantId`：当前选中的租户 ID
   - `role`：用户角色（`admin` / `user` / `viewer`）
   - `displayName`：用户显示名称
4. **自动跳转**：登录成功后自动跳转到首页 `/`

#### 5.2 后端 API 登录（备用）

**登录获取 Token：**
```bash
POST http://localhost:8080/api/auth/login
Content-Type: application/json

{
  "username": "admin",
  "password": "admin123"
}
```

**响应：**
```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "expireIn": 7200000,
    "userId": 1,
    "tenantIds": [1, 2],
    "currentTenantId": 1,
    "role": "admin",
    "displayName": "admin"
  }
}
```

#### 5.3 使用 Token 访问 API

**手动调用 API 时，需要在请求头中添加：**
```bash
GET http://localhost:8080/api/document/list
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
X-Tenant-Id: 1
```

**说明：**
- `Authorization`：JWT Token，前端从 `localStorage` 自动读取并添加
- `X-Tenant-Id`：当前租户 ID，后端会验证是否在 Token 的 `tenantIds` 列表中

#### 5.4 租户切换

1. **进入租户管理**：点击首页顶部"🏢 租户"按钮
2. **选择租户**：在租户列表中点击"选择"按钮
3. **自动更新**：前端自动更新 `localStorage` 中的 `currentTenantId`，后续 API 调用使用新租户 ID

**权限控制：**
- 用户只能选择其 `tenantIds` 列表中的租户
- 非 `admin` 角色用户不可见"创建租户"和"删除租户"按钮

#### 5.5 Token 刷新和过期处理

**自动刷新（前端）：**
- Token 有效期 2 小时（7200 秒）
- 前端检测到 401 错误时，自动跳转到登录页

**手动刷新（API）：**
```bash
POST http://localhost:8080/api/auth/refresh
Content-Type: application/json

{
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

#### 5.6 登出

**前端登出：**
- 点击首页顶部"🚪 登出"按钮
- 自动清除 `localStorage` 中的所有认证信息
- 跳转到登录页

**后端 API：**
```bash
POST http://localhost:8080/api/auth/logout
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

### 6. Docker Compose 完整部署

```bash
export DASHSCOPE_API_KEY=sk-your-api-key
export SILICONFLOW_API_KEY=sk-your-siliconflow-key
docker compose up -d
```

## Docker 部署指南

> 📌 **需要部署到 Kubernetes？** 参见 [Kubernetes 部署指南](docs/k8s-deployment.md)

### 前置条件

- Docker 20.10+
- Docker Compose 2.0+
- 至少 4GB 可用内存
- 模型 API Key（DashScope、SiliconFlow）

### 部署步骤

#### 1. 克隆项目

```bash
git clone https://github.com/your-org/CompanyRag.git
cd CompanyRag
```

#### 2. 配置环境变量

**方式一：使用 .env.docker 文件（推荐）**

```bash
# 步骤 1：复制 Docker 部署环境变量模板
copy company-rag-bootstrap\.env.docker.example company-rag-bootstrap\.env.docker  # Windows
cp company-rag-bootstrap/.env.docker.example company-rag-bootstrap/.env.docker    # Linux/Mac

# 步骤 2：编辑 .env.docker 文件，填写真实配置（见下方变量说明）

# 步骤 3（可选）：如果需要使用 .env 文件，复制一份
copy company-rag-bootstrap\.env.docker company-rag-bootstrap\.env  # Windows
cp company-rag-bootstrap/.env.docker company-rag-bootstrap/.env    # Linux/Mac
```

编辑 `company-rag-bootstrap/.env.docker` 文件，配置以下必需变量：

```bash
# 【必须配置】API Keys
DASHSCOPE_API_KEY=sk-your-dashscope-api-key
SILICONFLOW_API_KEY=sk-your-siliconflow-api-key

# 【必须配置】数据库配置（容器间通信使用容器名称）
POSTGRES_HOST=docker-pgvector-1
POSTGRES_PORT=5432
POSTGRES_DB=company_rag
POSTGRES_USER=postgres
POSTGRES_PASSWORD=your_strong_password_here

# 【必须配置】Redis 配置
REDIS_HOST=docker-redis-1
REDIS_PORT=6379
REDIS_PASSWORD=your_strong_password_here

# 【必须配置】JWT 密钥（Base64 编码）
# 生成方法：openssl rand -base64 32 | tr -d '\n'
JWT_SECRET=Y29tcGFueS1yYWctZG9ja2VyLWRlcGxveW1lbnQtc2VjcmV0LWtleS1mb3Itand0LXRva2VuLTIwMjY=

# 【可选】服务端口
SERVER_PORT=8080
SPRING_PROFILES_ACTIVE=prod
```

**方式二：使用 docker-compose.yml 的环境变量**

```bash
# 设置环境变量
export DASHSCOPE_API_KEY=sk-your-api-key
export SILICONFLOW_API_KEY=sk-your-siliconflow-api-key
export POSTGRES_PASSWORD=your_strong_password
export REDIS_PASSWORD=your_strong_password
export JWT_SECRET=$(openssl rand -base64 32 | tr -d '\n')

# 启动容器
docker compose up -d
```

#### 3. 启动基础设施容器

```bash
# 启动 PostgreSQL (PGVector) + Redis
docker compose up -d postgres redis

# 验证容器状态
docker ps

# 预期输出：
# CONTAINER ID   IMAGE                    STATUS         PORTS                    NAMES
# xxxxxxx        redis:7-alpine           Up 10 seconds  0.0.0.0:6379->6379/tcp   docker-redis-1
# xxxxxxx        pgvector/pgvector:pg16   Up 10 seconds  0.0.0.0:5433->5432/tcp   docker-pgvector-1
```

#### 4. 构建应用镜像

**方式一：本地 Maven 构建 + Docker 打包（推荐，利用本地缓存）**

```bash
# 本地 Maven 构建（约 1-2 分钟）
mvn clean package -DskipTests -pl company-rag-bootstrap -am

# 构建 Docker 镜像（约 2-3 分钟）
docker build -t company-rag:latest .
```

**方式二：Docker 多阶段构建（无需本地 Maven）**

```bash
# 直接在 Docker 内完成 Maven 构建和打包（首次约 10-15 分钟）
docker build -t company-rag:latest --target full-build .
```

#### 5. 部署应用容器

```bash
# 创建 Docker 网络（如果不存在）
docker network create my-ai-network

# 启动应用容器
docker run -d \
  --name company-rag-1 \
  --network my-ai-network \
  -p 8080:8080 \
  --env-file company-rag-bootstrap/.env \
  -e PYTHON_EXEC_PATH=/usr/bin/python \
  company-rag:latest
```

**Windows PowerShell 示例：**

```powershell
docker run -d `
  --name company-rag-1 `
  --network my-ai-network `
  -p 8080:8080 `
  --env-file company-rag-bootstrap/.env `
  -e PYTHON_EXEC_PATH=/usr/bin/python `
  company-rag:latest
```

#### 6. 验证部署

```bash
# 查看容器状态
docker ps | grep company-rag

# 查看应用日志
docker logs -f company-rag-1

# 预期看到以下日志表示启动成功：
# CompanyRagApplication - Started CompanyRagApplication in XX.XXX seconds
# o.s.b.w.embedded.tomcat.TomcatWebServer - Tomcat started on port(s): 8080 (http)
```

#### 7. 访问系统

| 服务 | 地址 | 说明 |
|------|------|------|
| 应用 | http://localhost:8080 | 知识库首页 |
| PostgreSQL | localhost:5433 | 数据库（外部访问） |
| Redis | localhost:6379 | 缓存（外部访问） |
| Prometheus | http://localhost:9090 | 监控指标 |
| Grafana | http://localhost:3000 | 可视化（admin/admin） |

**首次登录：**
- 用户名：`admin`
- 密码：`admin123`
- ⚠️ 首次登录后请立即修改密码

### 容器网络配置

**容器间通信拓扑：**

```
┌─────────────────┐      my-ai-network      ┌─────────────────┐
│  company-rag-1  │ ◄────────────────────► │  docker-pgvector-1│
│   (应用容器)     │                         │   (PostgreSQL)    │
│                 │                         │                   │
│                 │ ◄────────────────────► │  docker-redis-1   │
│                 │                         │   (Redis)         │
└─────────────────┘                         └─────────────────┘
```

**关键配置说明：**

1. **容器名称**：
   - PostgreSQL: `docker-pgvector-1`
   - Redis: `docker-redis-1`
   - 应用：`company-rag-1`

2. **网络模式**：
   - 所有容器连接到 `my-ai-network` 专用网络
   - 容器间通过容器名称直接通信（DNS 解析）

3. **环境变量传递**：
   - `.env` 文件中的 `POSTGRES_HOST=docker-pgvector-1` 指向 PostgreSQL 容器
   - `.env` 文件中的 `REDIS_HOST=docker-redis-1` 指向 Redis 容器

### 常见问题排查

#### 1. 容器启动失败

**问题：** `Error creating bean with name 'redisson'`

**原因：** Redis 容器名称或网络配置不匹配

**解决：**
```bash
# 检查容器网络
docker network inspect my-ai-network

# 确认 Redis 容器名称
docker ps | grep redis

# 修改 .env 中的 REDIS_HOST 与实际容器名称一致
```

#### 2. JWT_SECRET 格式错误

**问题：** `Illegal base64 character 2d`

**原因：** JWT_SECRET 不是有效的 Base64 编码

**解决：**
```bash
# 重新生成 Base64 编码的密钥
openssl rand -base64 32 | tr -d '\n'

# 更新 .env 文件
JWT_SECRET=生成的 Base64 字符串
```

#### 3. 数据库连接失败

**问题：** `Failed to resolve 'docker-pgvector-1'`

**原因：** 容器不在同一网络

**解决：**
```bash
# 将容器连接到同一网络
docker network connect my-ai-network docker-pgvector-1
docker network connect my-ai-network company-rag-1
```

#### 4. 内存不足

**问题：** 容器启动后自动退出，日志显示 OOM

**解决：**
```bash
# 增加 Docker 内存限制（Windows/Mac）
# Docker Desktop → Settings → Resources → Memory → 调整为 4GB+

# 或者限制 JVM 堆内存
docker run -d \
  -e JAVA_TOOL_OPTIONS="-Xmx2g" \
  company-rag:latest
```

#### 5. Python 依赖安装失败

**问题：** Docker 构建时 numpy 等包编译失败

**原因：** 缺少编译工具或 PEP 668 限制

**解决：** Dockerfile 已包含以下配置：
- 安装 `build-essential` 编译工具
- 使用 `--break-system-packages` 绕过 PEP 668
- 使用清华镜像源加速下载

### 清理和重置

```bash
# 停止所有容器
docker compose down

# 删除应用容器
docker stop company-rag-1
docker rm company-rag-1

# 删除所有容器和网络（谨慎操作）
docker compose down -v
docker network prune -f

# 删除镜像
docker rmi company-rag:latest
```

### 生产环境建议

1. **使用 Docker Compose 管理所有容器**（推荐）
2. **环境变量加密存储**（使用 Docker Secrets 或 Vault）
3. **定期备份数据库**
4. **配置日志收集**（ELK Stack 或 Loki）
5. **启用 HTTPS**（使用 Nginx 反向代理 + Let's Encrypt）
6. **配置健康检查和自动重启**
7. **使用固定版本镜像标签**（避免 `latest` 标签的不确定性）

### 性能调优

**JVM 参数优化：**
```bash
docker run -d \
  -e JAVA_TOOL_OPTIONS="-Xms2g -Xmx4g -XX:+UseG1GC" \
  company-rag:latest
```

**数据库连接池优化：**
```yaml
# application-prod.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
```

**Redis 连接池优化：**
```yaml
spring:
  redis:
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 5
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

### 用户管理（仅管理员）
```bash
# 创建用户
POST /api/user
Content-Type: application/json
X-Tenant-Id: 1
{
  "username": "newuser",
  "password": "initial123",
  "displayName": "新用户",
  "email": "user@example.com",
  "role": "user",
  "tenantIds": [1, 2]
}

# 查询用户列表（支持筛选）
GET /api/user/list?role=user&tenantId=1&status=1&username=test
X-Tenant-Id: 1

# 查询用户详情
GET /api/user/{userId}
X-Tenant-Id: 1

# 更新用户
PUT /api/user/{userId}
Content-Type: application/json
X-Tenant-Id: 1
{
  "displayName": "更新后的名称",
  "email": "new@example.com",
  "role": "admin",
  "tenantIds": [1],
  "password": "" // 留空表示不修改密码
}

# 删除用户（级联删除关联表数据）
DELETE /api/user/{userId}
X-Tenant-Id: 1
```

**权限控制：**
- 所有用户管理 API 需要 `admin` 角色权限（后端 `@PreAuthorize("hasRole('ADMIN')")`）
- 前端仅管理员可见"👤 用户"导航按钮

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
n### 认证说明

**所有 API 请求需要在 Header 中携带 JWT Token：**
```
Authorization: Bearer <your-token>
```

Token 通过登录接口 `/api/auth/login` 获取，有效期 2 小时。过期后使用刷新令牌 `/api/auth/refresh` 刷新。

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
- **搜索范围**：`${app.code-search.src-base}`（默认项目根目录 `user.dir`）
- **配置说明**：
  - 默认值：项目根目录（应用启动时的工作目录）
  - 自定义路径：通过环境变量 `CODE_SEARCH_SRC_BASE` 或配置文件 `app.code-search.src-base` 指定
  - 适用场景：Docker 部署、特殊项目结构、多代码仓库联合搜索

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

**重要说明**：Flyway 数据库版本管理工具**已启用**，应用启动时会自动执行迁移脚本。

#### 首次部署（推荐）

**首次启动应用时，Flyway 会自动执行以下迁移脚本：**

1. `V1__fix_tenant_isolation_security.sql` - 多租户隔离安全修复
2. `V2__fix_database_query_tool_cross_tenant_access.sql` - DatabaseQueryTool 跨租户访问修复
3. `V3__init_platform_admin.sql` - 系统初始化（创建 admin 账号和默认租户）

**无需手动执行 SQL 脚本！** 应用启动后会自动完成初始化。

**验证初始化完成：**
```bash
# 检查 Flyway 迁移历史
psql -h localhost -U postgres -d company_rag -c "SELECT version, description, state FROM flyway_schema_history ORDER BY installed_rank;"

# 验证 admin 账号创建成功
psql -h localhost -U postgres -d company_rag -c "SELECT username, role FROM sys_user WHERE username='admin';"

# 验证默认租户创建成功
psql -h localhost -U postgres -d company_rag -c "SELECT tenant_code, tenant_name FROM sys_tenant WHERE tenant_code='tenant_default';"
```

**初始化脚本会创建：**
- ✅ 平台级超级管理员账号：`admin`（密码：`admin123`）
- ✅ 默认租户：`tenant_default`（租户名称：默认租户）
- ✅ admin 与默认租户的关联关系（`sys_user_tenant_rel`）
- ✅ 默认租户的 schema：`tenant_tenant_default`
- ✅ 5 个业务表：`vector_store`、`doc_chunk`、`rag_document`、`rag_session`、`rag_session_meta`
- ✅ 索引（包括 HNSW 向量索引）
- ✅ RLS（行级安全）策略
- ✅ 数据库用户 `company_rag_app` 权限

**重要提示：**
- ⚠️ 首次登录后请立即修改 admin 密码
- 📋 默认租户供 admin 首次登录使用
- 🔒 admin 账号是平台级超级管理员，创建新租户时会自动关联
- 🔄 Flyway 配置见 `application.yml` 中的 `flyway.*` 配置项

#### 已部署系统升级

**如果系统已有数据库，Flyway 会自动检测并执行基线标记（baseline-on-migrate），不会重新执行已存在的脚本。**

**新增迁移脚本步骤：**
1. 在 `company-rag-bootstrap/src/main/resources/db/migration/` 目录创建新文件
2. 命名格式：`V<版本>__<描述>.sql`（版本号严格递增：V4 → V5 → V6）
3. 脚本必须幂等（使用 `IF EXISTS`、`IF NOT EXISTS`）
4. 重启应用，Flyway 会自动执行新脚本

**示例：**
```sql
-- V4__add_user_avatar_column.sql
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(512);
```

#### 方式二：使用 DBeaver 等 GUI 工具

打开 DBeaver → 连接数据库 → 右键 SQL 文件 → 执行脚本（仅用于手动测试，生产环境建议使用 Flyway 自动管理）

**Flyway 配置详情：**

```yaml
flyway:
  enabled: true                    # 已启用
  locations: classpath:db/migration
  baseline-on-migrate: true        # 已有数据库自动基线标记
  baseline-version: 0
  validate-on-migrate: true        # 校验迁移脚本
  clean-disabled: true             # 禁用 clean 操作（生产安全）
  migrate-at-startup: true         # 启动时自动执行迁移
```

详见：`company-rag-bootstrap/src/main/resources/db/migration/README.md`

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
