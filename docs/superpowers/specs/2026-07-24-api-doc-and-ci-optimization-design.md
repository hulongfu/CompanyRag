# CompanyRag 优化设计：API 文档自动生成 + Gitee Go CI/CD

## 概述

本文档针对 CompanyRag 项目当前缺乏 API 文档和自动化构建流程的问题，提出两个优化方向的设计方案：

1. **API 文档自动生成**：集成 SpringDoc OpenAPI 3.0，为所有 REST API 生成标准 OpenAPI 文档并提供 Swagger UI 交互界面。
2. **CI/CD 管道搭建**：基于 Gitee Go（Gitee 官方 CI 平台）搭建自动化构建与测试流水线，提升代码质量和交付效率。

---

## 1. API 文档自动生成

### 1.1 现状

项目现有 5 个 REST Controller，共计 15+ API 端点：

| Controller | 端点数 | 功能 |
|---|---|---|
| `RagController` | 3 | RAG 检索/流式回答/文档检索 |
| `DocumentController` | 3 | 文档上传/列表/删除 |
| `SessionController` | 3 | 会话 CRUD |
| `AgentController` | 2 | Agent 对话 |
| `TenantController` | 4+ | 租户/用户管理 |

当前无任何 API 文档，前端对接需阅读代码或手动沟通。

### 1.2 技术选型

选用 **SpringDoc OpenAPI 3.0**（`springdoc-openapi-starter-webmvc-ui`），原因：
- Spring Boot 3.4 原生支持，兼容性最好
- 自动扫描 `@RestController` 生成文档，零侵入
- 内置 Swagger UI，可交互式调试
- 社区活跃，维护良好

### 1.3 实施步骤

#### 1.3.1 添加 Maven 依赖

在 `company-rag-web/pom.xml` 中添加：

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.8.6</version>
</dependency>
```

#### 1.3.2 添加配置

在 `application.yml` 中添加 SpringDoc 配置：

```yaml
springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: method
  show-actuator: false
```

#### 1.3.3 创建 OpenAPI 配置类

在 `company-rag-web` 模块中创建配置类，定义 API 全局信息：

- API 标题：CompanyRag API
- 版本：1.0.0
- 全局请求头：`X-Tenant-Id`（租户 ID）、`X-User-Id`（用户 ID）
- 服务器地址：从配置读取

#### 1.3.4 添加 Controller 注解（可选增强）

在关键 Controller 和模型上添加 `@Tag`、`@Operation`、`@Schema` 注解，提升文档可读性。不做大规模改动，仅对主要端点添加说明。

### 1.4 预期效果

- 访问 `http://localhost:8080/swagger-ui.html` 可查看所有 API
- 每个 API 显示：请求方法、路径、参数、请求体、响应格式
- 支持在线调试（Try it out）
- 自动生成 `/api-docs` OpenAPI JSON，可导入 Postman 等工具

### 1.5 不涉及变更

- 不修改任何业务逻辑代码
- 不引入新的安全风险（SpringDoc 通过 Actuator 配置控制生产环境暴露）
- 不影响现有 API 的请求/响应格式

---

## 2. CI/CD 管道搭建（Gitee Go）

### 2.1 现状

- 远程仓库托管在 Gitee: `https://gitee.com/LongHuDaoChang/CompanyRag.git`
- 当前部署流程：手动 git pull → mvn package → docker-compose up
- 无自动化构建、测试、质量检查

### 2.2 技术选型

选用 **Gitee Go**（Gitee 官方 CI/CD 平台），原因：
- 与 Gitee 仓库深度集成，无需额外配置 WebHook
- 提供免费构建额度，适合本项目规模
- 配置简单（YAML 定义流水线）
- 支持 Maven 项目、Docker 构建

### 2.3 流水线设计

```
流水线：CompanyRag CI
├── 阶段1: 构建与测试
│   ├── mvn clean test           # 运行单元测试
│   └── mvn package -DskipTests  # 构建 JAR
│
└── 阶段2: Docker 镜像构建（仅标签触发）
    └── docker build -t company-rag:${TAG}
```

**触发规则**：
| 事件 | 触发阶段 | 用途 |
|---|---|---|
| push 到 main 分支 | 阶段1 | 每次提交验证构建和测试 |
| 打 v* 标签 | 阶段1 + 阶段2 | 发布时构建镜像 |
| 手动触发 | 全部 | 按需执行 |

**不包含**：自动部署到生产环境（按需求排除）。

### 2.4 实施步骤

#### 2.4.1 创建 Gitee Go 配置文件

创建 `.gitee/workflows/ci.yml`：

```yaml
pipeline:
  name: CompanyRag CI
  trigger:
    push:
      - main
    tag:
      - v*
  stages:
    - stage: Build and Test
      jobs:
        - job: Maven Build
          steps:
            - uses: maven@3.9
              with:
                args: clean test
            - uses: maven@3.9
              with:
                args: package -DskipTests
            - run: echo "构建完成，JAR 包已生成"
    - stage: Docker Build
      jobs:
        - job: Docker Build
          steps:
            - run: docker build -t company-rag:${GITEE_TAG} .
```

#### 2.4.2 配置 Gitee Go 环境变量

在 Gitee 仓库的「管理 → Gitee Go → 环境变量」中配置：
- `DASHSCOPE_API_KEY`：DashScope API 密钥（用于集成测试）
- `SILICONFLOW_API_KEY`：硅基流动 API 密钥（用于集成测试）

#### 2.4.3 配置 Docker Hub 认证（可选，用于镜像推送）

如需推送 Docker 镜像到镜像仓库，在 Gitee Go 中配置：
- `DOCKER_USERNAME`：Docker Hub 用户名
- `DOCKER_PASSWORD`：Docker Hub 密码

### 2.5 预期效果

- 每次 push 到 main 分支自动触发构建和测试
- 构建失败时 Gitee 自动通知（Commit 状态标记）
- 打标签时自动构建 Docker 镜像
- 减少人工操作失误，提升交付效率

### 2.6 不涉及变更

- 不修改项目源代码（仅新增配置文件）
- 不影响现有部署流程
- 不涉及生产环境部署

---

## 3. 文件变更清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `company-rag-web/pom.xml` | 修改 | 添加 SpringDoc 依赖 |
| `company-rag-bootstrap/src/main/resources/application.yml` | 修改 | 添加 SpringDoc 配置 |
| `company-rag-web/src/main/java/.../config/OpenApiConfig.java` | 新增 | OpenAPI 全局配置类 |
| `.gitee/workflows/ci.yml` | 新增 | Gitee Go 流水线定义 |

## 4. 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|---|---|---|
| SpringDoc 在生产环境暴露 API 文档 | 信息泄露 | 通过 `springdoc.show-actuator=false` + 生产环境 profile 控制 |
| Gitee Go 构建超时 | 流水线失败 | 优化 Maven 构建时间，使用 `dependency:go-offline` 缓存依赖 |
| 测试依赖外部 API（DashScope） | 构建失败 | 集成测试使用 `@IfProfileValue` 或 Mock 隔离 |

---

## 5. 审批

| 角色 | 姓名 | 日期 | 意见 |
|---|---|---|---|
| 设计者 | AI | 2026-07-24 | - |
| 审批人 | | | |