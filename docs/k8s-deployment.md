# CompanyRag Kubernetes 部署指南

基于 Kubernetes 的企业知识库 RAG 系统部署说明。包含应用、PostgreSQL(PGVector)、Redis 的完整清单与健康探针配置。

## 清单文件一览

| 文件 | 用途 |
|------|------|
| `k8s/secret.yaml` | 敏感配置（LLM Key / JWT / 数据库密码） |
| `k8s/configmap.yaml` | 非敏感配置（服务地址 / 端口） |
| `k8s/initdb-configmap.yaml` | PostgreSQL 初始化脚本（含 `sql/init.sql` 内容） |
| `k8s/postgres.yaml` | PostgreSQL 16 + PGVector 部署（Deployment + Service + PVC） |
| `k8s/redis.yaml` | Redis 7 部署（Deployment + Service） |
| `k8s/deployment.yaml` | 应用部署（Deployment + Service，含健康探针） |

## 前置条件

- Kubernetes 1.24+ 集群
- 已构建应用镜像 `company-rag:latest`（见根 README「Docker 部署指南」构建步骤）
- 模型 API Key（DashScope、SiliconFlow）

## 部署步骤

### 1. 生成并替换 Secret 真实值

`k8s/secret.yaml` 中所有字段为 base64 编码，部署前必须替换为真实值：

```bash
# 生成 base64
echo -n "真实值" | base64

# JWT 密钥建议强随机
openssl rand -base64 48 | tr -d '\n' | base64
```

| 字段 | 说明 | 是否必须替换 |
|------|------|------|
| `DASHSCOPE_API_KEY` | 通义千问 Chat API Key | 是 |
| `SILICONFLOW_API_KEY` | Embedding / Rerank API Key | 是 |
| `JWT_SECRET` | JWT 签名密钥 | 是 |
| `POSTGRES_PASSWORD` | 应用用户 `company_rag_app` 密码 | 是 |
| `POSTGRES_SUPERUSER_PASSWORD` | postgres 主进程超级用户密码 | 是 |

> ⚠️ **注意**：如果更换了 `POSTGRES_PASSWORD`（应用库密码），必须同步修改
> `k8s/initdb-configmap.yaml` 中 `CREATE USER company_rag_app ... PASSWORD '...'`，
> 两处不一致会导致应用无法连接数据库。当前两处默认值一致为 `company_rag_app123456`。

### 2. 按需调整 ConfigMap

`k8s/configmap.yaml` 默认指向集群内 Service 名 `postgres` / `redis`。
若 PostgreSQL / Redis 部署在集群外部或名称不同，需修改 `POSTGRES_HOST`、`POSTGRES_PORT`、`REDIS_HOST`、`REDIS_PORT`。

### 3. 依次应用清单

按依赖顺序应用（先基础设施，后应用）：

```bash
# 配置
kubectl apply -f k8s/secret.yaml k8s/configmap.yaml
kubectl apply -f k8s/initdb-configmap.yaml

# 基础设施
kubectl apply -f k8s/postgres.yaml k8s/redis.yaml

# 应用（依赖上述资源就绪）
kubectl apply -f k8s/deployment.yaml
```

### 4. 验证部署

```bash
# 查看资源状态
kubectl get pods -l app=company-rag
kubectl get pods -l app=postgres
kubectl get pods -l app=redis

# 查看应用日志
kubectl logs deploy/company-rag

# 预期看到：
# CompanyRagApplication - Started ... 
# Tomcat started on port(s): 8080 (http)

# 检查就绪状态（3 个容器均 Running 且 READY 1/1）
kubectl get pods
```

## 健康探针机制

应用使用 Spring Boot Actuator 探针端点，三条链路路径完全对齐：

| 探针 | 路径 | 说明 |
|------|------|------|
| liveness | `/actuator/health/liveness` | 存活探针，进程僵死时重启 |
| readiness | `/actuator/health/readiness` | 就绪探针，未就绪时摘除流量 |

- `application.yml` 已开启 `management.endpoint.health.probes.enabled: true`
- `SecurityConfig` 已放行 `/actuator/health/**`（含 `/liveness`、`/readiness` 子路径）
- `k8s/deployment.yaml` 配置 livenessProbe/readinessProbe
- `Dockerfile` 与 `docker-compose.yml` 的 HEALTHCHECK 采用同一路径，三链路一致

## 访问服务

```bash
# 端口转发（本地访问）
kubectl port-forward service/company-rag 8080:8080
```

浏览器访问 `http://localhost:8080`，首次登录：
- 用户名：`admin`
- 密码：`admin123`
- ⚠️ 首次登录后请立即修改密码

## 备份与恢复

PostgreSQL 使用 PVC 持久化（默认 10Gi）。备份：

```bash
kubectl exec deploy/postgres -- pg_dump \
  -U postgres -d company_rag -F c -f /tmp/backup.dump

# 拷贝到本地
kubectl cp postgres:/tmp/backup.dump ./backup.dump
```

## 常见问题排查

| 现象 | 可能原因 | 处理 |
|------|---------|------|
| 应用无法连接数据库 | `POSTGRES_PASSWORD` 与 init 脚本不一致 | 核对两处密码一致（见上文） |
| RLS 未生效 / 数据泄露风险 | 应用用了超级用户连接 | 应用必须用 `company_rag_app`（非超户）连接 |
| 就绪探针失败 | 数据库/Redis 未就绪或依赖服务名不匹配 | 检查 configmap 中 HOST，`kubectl get pods` 查依赖 |
| Pod 权限拒绝 | 镜像 UID 与 runAsUser 不一致 | 镜像已钉死 UID/GID=1000，与 `runAsUser: 1000` 对齐 |
| init.sql 未执行 | PVC 已存在，postgres 跳过首次初始化 | 仅空数据卷首次启动会执行 init 脚本，须清理 PVC 后重装 |

## 安全注意事项

- **最小权限 + RLS**：postgres 主进程用独立超级用户 `postgres`（仅负责初始化）；
  应用使用非超户 `company_rag_app` 连接，RLS 策略 `FORCE ROW LEVEL SECURITY` 才能生效。
  切勿让应用以 `postgres` 超级用户连接——超级用户会绕过 RLS，破坏多租户隔离。
- 所有密钥必须替换并妥善保管，避免硬编码与泄露。
- 生产建议配置 Ingress + TLS，并开启资源配额（deployment 已预设 request/limit）。