# 企业级加固设计文档 — CompanyRag

> 将 CompanyRag 从 Beta 阶段推进到**生产就绪**状态的设计方案  
> 日期：2026-08-02  
> 状态：草稿（待审阅）

---

## 1. 背景与目标

CompanyRag 已实现完整的 RAG 全链路、多租户架构、Agent 编排等核心功能，但在安全认证、测试覆盖、生产部署三个维度存在明显缺口。本设计文档针对这三个方向进行系统性加固，目标是：

- **安全维度**：建立完整的认证/授权/审计体系，消除"无认证可访问"的安全隐患
- **质量维度**：核心模块测试覆盖率达到生产标准，CI 流水线自动检查代码质量
- **运维维度**：提供 Kubernetes 部署配置，降低生产环境部署门槛

### 执行顺序

1. **安全与认证** → 2. **测试与 CI/CD** → 3. **生产部署（K8s）**

每阶段完成后进入下一阶段，不做并行推进。

---

## 2. 阶段一：安全与认证体系

### 2.1 认证方案：JWT 无状态令牌

#### 认证流程

```
[客户端]                    [Spring Security Filter Chain]           [Controller]
    │                               │                                    │
    │  POST /api/auth/login          │                                    │
    │  {username, password}          │                                    │
    │ ──────────────────────────────►│                                    │
    │                               │  BCrypt 验证密码                    │
    │                               │  生成 Access Token (2h)            │
    │                               │  生成 Refresh Token (7d)           │
    │◄──────────────────────────────│                                    │
    │  {token, refreshToken, expire}│                                    │
    │                               │                                    │
    │  GET /api/chat                 │                                    │
    │  Authorization: Bearer <token> │                                    │
    │ ──────────────────────────────►│                                    │
    │                               │  JwtAuthenticationFilter            │
    │                               │  解析 token → SecurityContext       │
    │                               │  ├─ userId, tenantId, role          │
    │                               │────────────────────────────────────►│
    │                               │  @PreAuthorize 检查权限             │
    │◄──────────────────────────────│────────────────────────────────────│
```

#### JWT 结构

```json
{
  "sub": "用户ID",
  "tenantId": "租户ID",
  "role": "admin|user|viewer",
  "iat": 1690000000,
  "exp": 1690007200
}
```

- 签名算法：HS256（对称密钥）
- 密钥来源：环境变量 `JWT_SECRET`（通过 `.env` 注入）
- Access Token 有效期：2 小时
- Refresh Token 有效期：7 天

#### 模块变更

| 模块 | 变更内容 |
|------|---------|
| `company-rag-common` | 新增 `JwtTokenProvider`（生成/解析/校验 Token）+ `JwtProperties` |
| `company-rag-common` | 新增 `SecurityUser`（实现 `UserDetails`，携带 tenantId/role） |
| `company-rag-web` | 新增 `AuthController`（登录/刷新/登出） |
| `company-rag-web` | 新增 `AuthRequest / AuthResponse` DTO |
| `company-rag-bootstrap` | 新增 `SecurityConfig`（Spring Security 过滤器链） |
| `company-rag-bootstrap` | 新增 `JwtAuthenticationFilter`（OncePerRequestFilter） |

#### 接口定义

```
POST /api/auth/login     — 登录，返回 JWT
POST /api/auth/refresh   — 刷新 Token（需 Refresh Token）
POST /api/auth/logout    — 登出（将 Token 加入黑名单缓存）
```

> 登录失败连续 5 次，账户锁定 15 分钟（通过 Redis 计数缓存实现）

### 2.2 权限模型：角色级别（3 级）

沿用现有角色体系，不引入细粒度权限点。

| 角色 | 标签 | 权限范围 |
|------|------|---------|
| **管理员** | `ROLE_admin` | 全部权限：租户管理、文档 CRUD、缓存管理、用户管理 |
| **普通用户** | `ROLE_user` | 文档上传/查看、RAG 问答、Agent 工具调用、会话管理 |
| **只读用户** | `ROLE_viewer` | 仅查看：知识库浏览、会话历史查看、文档列表查看 |

#### 实现方式

- **方法级控制**：使用 `@PreAuthorize` 注解，如 `@PreAuthorize("hasRole('admin')")`
- **自定义权限表达式**：扩展 `MethodSecurityExpressionRoot`，支持 `@PreAuthorize("@ss.hasPermission('document:delete')")` 预留 RBAC 扩展点
- **统一异常处理**：`AccessDeniedException` → 403 响应（`R.fail(403, "权限不足")`）

#### 角色-接口映射

| 接口路径 | admin | user | viewer |
|---------|-------|------|--------|
| `/api/auth/**` | ✅ | ✅ | ✅ |
| `/api/chat/**` | ✅ | ✅ | ✅ |
| `/api/document/upload` | ✅ | ✅ | ❌ |
| `/api/document/delete/**` | ✅ | ❌ | ❌ |
| `/api/document/list` | ✅ | ✅ | ✅ |
| `/api/session/**` | ✅ | ✅ | ✅ |
| `/api/tenant/**` | ✅ | ❌ | ❌ |
| `/api/cache/**` | ✅ | ❌ | ❌ |
| `/api/agent/**` | ✅ | ✅ | ❌ |
| `/api/user/**` | ✅ | ❌ | ❌ |

### 2.3 审计日志：关键操作记录

#### 记录的操作类型

| 操作类型 | 说明 | 记录内容 |
|---------|------|---------|
| `LOGIN_SUCCESS` | 登录成功 | 用户名、IP、时间 |
| `LOGIN_FAILED` | 登录失败 | 用户名、IP、失败原因 |
| `LOGOUT` | 登出 | 用户名、IP |
| `DOCUMENT_UPLOAD` | 文档上传 | 文档名、大小、租户 |
| `DOCUMENT_DELETE` | 文档删除 | 文档 ID、文档名、租户 |
| `TENANT_CREATE` | 创建租户 | 租户名称、操作人 |
| `TENANT_DELETE` | 删除租户 | 租户 ID、操作人 |
| `CACHE_CLEAR` | 清理缓存 | 缓存名称、操作人 |
| `ROLE_CHANGE` | 角色变更 | 目标用户、旧角色、新角色 |

#### 数据表设计

```sql
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(32)  NOT NULL,    -- 租户 ID
    user_id     BIGINT       NOT NULL,    -- 操作人
    action_type VARCHAR(32)  NOT NULL,    -- 操作类型
    target_type VARCHAR(32),              -- 目标类型（document/tenant/cache/user）
    target_id   VARCHAR(64),              -- 目标 ID
    detail      TEXT,                      -- 操作详情（JSON）
    ip_address  VARCHAR(45),              -- 客户端 IP
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_tenant_action ON audit_log(tenant_id, action_type, created_at DESC);
CREATE INDEX idx_audit_log_created_at   ON audit_log(created_at DESC);
```

#### 实现方式

- 自定义注解 `@AuditLog(action = "DOCUMENT_DELETE")`
- Spring AOP 环绕通知，在方法执行后异步写入
- 使用 `@Async` + 独立线程池，不阻塞业务请求
- 审计日志表按租户隔离（通过 `tenant_id` 字段 + 行级安全策略 RLS）

#### 模块变更

| 模块 | 变更内容 |
|------|---------|
| `company-rag-common` | 新增 `@AuditLog` 注解 + `AuditLogAspect` |
| `company-rag-common` | 新增 `AuditLogService` 接口 |
| `company-rag-tenant` | 新增 `AuditLogMapper` + `AuditLogEntity` |
| `company-rag-tenant` | 新增 `AuditLogServiceImpl`（异步写入） |

### 2.4 密码与用户管理

- 密码加密：BCrypt（Spring Security `PasswordEncoder` 默认实现）
- 用户表：复用现有的 `tenant_user` 表，增加 `password_hash` 字段
- 登录失败锁定：Redis 缓存计数，key = `login:fail:{username}`，TTL 15 分钟
- 验证码：第一期不做，后续通过 `CaptchaFilter` 扩展

---

## 3. 阶段二：测试覆盖与 CI/CD 质量门禁

### 3.1 JaCoCo 测试覆盖率

**配置目标：**
- 统计粒度：**指令覆盖率（Instruction）**
- 核心模块阈值：≥ 80%（仅警告，不阻断构建）
- 统计范围：`company-rag-rag`、`company-rag-agent`、`company-rag-document` 三个核心业务模块

**Maven 配置：**
- 在根 `pom.xml` 中配置 `jacoco-maven-plugin` 插件
- `prepare-agent` 绑定到 `initialize` 阶段
- `report` 绑定到 `verify` 阶段
- `check` 绑定到 `verify` 阶段，设置 `instructionCoverage` 的 `minimum` 为 0.80

**排除规则：**
- 排除实体类（`**/entity/**`）
- 排除配置类（`**/config/**`）
- 排除 DTO/请求模型

### 3.2 Checkstyle 代码风格检查

**配置目标：**
- 基于现有 `conventions.md` 编码规范，编写 `checkstyle.xml`
- 仅警告不阻断（`maxErrors=0, maxWarnings=9999`）
- 在 CI 中生成 HTML 报告

**关键检查项：**
- 缩进 4 空格，K&R 大括号风格
- 行宽 ≤ 120 字符
- 命名规范（PascalCase、camelCase、UPPER_SNAKE_CASE）
- 导入顺序规范
- 单方法不超过 50 行

### 3.3 CI 流水线更新

**更新后的 Gitee CI 流水线：**

```
阶段 1: Maven 构建与质量检查
  ├── mvn clean compile          (编译检查)
  ├── mvn checkstyle:check       (代码风格，仅警告)
  ├── mvn test                   (单元测试，排除集成测试)
  └── mvn jacoco:check           (覆盖率检查，仅警告)

阶段 2: Docker 镜像构建（仅标签触发，不变）
```

**补充说明：**
- 当前 CI 在 Gitee 上运行，Gitee CI 环境可能没有 PostgreSQL/Redis，因此集成测试仍需排除
- 本地开发可通过 `mvn verify -Pintegration-test` 运行完整测试

---

## 4. 阶段三：Kubernetes 部署配置

### 4.1 目标

为 CompanyRag 编写完整的 Kubernetes 部署 YAML 配置，支持在本地 Docker Desktop K8s 或生产 K8s 集群中一键部署。

### 4.2 服务清单

| 服务 | 镜像 | 说明 |
|------|------|------|
| `company-rag-app` | 项目 Docker 镜像 | Spring Boot 应用，2 副本 |
| `postgres` | `postgres:16-alpine` | PostgreSQL + PGVector |
| `redis` | `redis:7-alpine` | Redis 缓存 + Redisson |
| `prometheus` | `prom/prometheus` | 指标采集 |
| `grafana` | `grafana/grafana` | 可视化面板 |

### 4.3 K8s 资源配置

| 资源类型 | 数量 | 说明 |
|---------|------|------|
| `Namespace` | 1 | `company-rag` 命名空间隔离 |
| `Deployment` | 5 | 每个服务一个 Deployment |
| `Service` | 5 | ClusterIP 对内暴露 |
| `ConfigMap` | 3 | 应用配置、Prometheus 配置、Grafana 配置 |
| `Secret` | 2 | 数据库密码、JWT 密钥 |
| `PersistentVolumeClaim` | 3 | PostgreSQL 数据、Prometheus 数据、Grafana 数据 |
| `Ingress` | 1 | 对外暴露应用（可选，按需启用） |
| `HorizontalPodAutoscaler` | 1 | 应用自动扩缩（可选） |

### 4.4 部署文档

编写 `deploy/k8s/README.md`，包含：
- Docker Desktop 开启 K8s 的步骤
- 使用 `kubectl apply -f deploy/k8s/` 部署
- 验证部署状态的方法
- 日志查看与问题排查指南

### 4.5 目录结构

```
deploy/k8s/
├── namespace.yaml
├── configmap.yaml           # 应用配置
├── secret.yaml              # 敏感信息（模板，实际值通过环境变量注入）
├── postgres/
│   ├── deployment.yaml
│   ├── service.yaml
│   └── pvc.yaml
├── redis/
│   ├── deployment.yaml
│   ├── service.yaml
│   └── pvc.yaml
├── app/
│   ├── deployment.yaml
│   ├── service.yaml
│   └── hpa.yaml
├── prometheus/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── configmap.yaml
│   └── pvc.yaml
├── grafana/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── configmap.yaml
│   └── pvc.yaml
├── ingress.yaml
├── kustomization.yaml       # Kustomize 组织所有资源
└── README.md
```

---

## 5. 不在此范围内的内容

以下内容虽已识别为缺口，但不在本次加固范围内，留待后续处理：

- **细粒度 RBAC 权限点**（当前角色级足够，预留扩展点）
- **OAuth2/SSO 集成**（JWT 预留扩展接口，后续可对接）
- **SonarQube 静态分析**（项目稳定后再引入）
- **ELK/Loki 日志聚合**（当前 Actuator + Prometheus 已覆盖基本可观测性）
- **生产环境性能压测**（部署到生产环境前单独安排）
- **HTTPS 证书配置**（依赖具体部署环境，在 K8s Ingress 层配置）
- **验证码登录**（后续通过 `CaptchaFilter` 扩展）
- **前端页面优化**（当前已有基本页面，后续单独规划）

---

## 6. 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| JWT Secret 泄露 | 可伪造任意 Token | 通过环境变量注入，定期轮换 |
| Token 撤销延迟 | 登出后 Token 在有效期内仍可用 | 引入 Redis 黑名单缓存 |
| 审计日志写入性能 | 高并发下可能影响业务 | 异步写入 + 独立线程池 |
| 测试覆盖率目标过高 | 可能拖延开发进度 | 设定为警告不阻断，逐步提升 |
| K8s 配置与生产环境差异 | 本地验证通过但生产环境不兼容 | 通过 ConfigMap 和环境变量分离配置 |

---

## 7. 附录：当前项目状态回顾

| 维度 | 评分 | 目标 | 本设计覆盖 |
|------|------|------|-----------|
| 功能完整性 | 4/5 | 5/5 | ❌（非本次范围） |
| 代码质量 | 3/5 | 4/5 | ✅ Checkstyle |
| 测试覆盖 | 2/5 | 4/5 | ✅ JaCoCo 80% |
| 安全防护 | 2/5 | 4/5 | ✅ JWT + 权限 + 审计 |
| 可观测性 | 3/5 | 4/5 | ❌（已基本就绪） |
| 部署运维 | 3/5 | 4/5 | ✅ K8s 配置 |
| 文档完善 | 4/5 | 5/5 | ✅ 补充部署文档 |
| CI/CD | 2/5 | 3/5 | ✅ 质量门禁 |