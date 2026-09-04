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

**方式一（推荐）：脚本自动生成**。新建 `k8s/generate-secret.sh` 从根目录 `.env`（已在 `.gitignore`）读取真实密钥，
动态生成 `k8s/secret.generated.yaml`，真实值不写入版本库内任何文件：

```bash
# 1. 从 .env.example 复制并填写真实密钥（DASHSCOPE_API_KEY / SILICONFLOW_API_KEY / JWT_SECRET / POSTGRES_PASSWORD）
cp .env.example .env

# 2. 生成 Secret 清单
bash k8s/generate-secret.sh

# 3. 应用
kubectl apply -f k8s/secret.generated.yaml
```

生成产物 `k8s/secret.generated.yaml` 已被 `.gitignore` 忽略，可安全在本地生成、提交部署。

**方式二：手工修改 `k8s/secret.yaml`**（提交到仓库的占位模板，含 base64 编码的占位值，跨环境部署时按需替换）：

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

PostgreSQL 使用 PVC 持久化（pgdata 默认 10Gi）。本项目提供两级数据保护：

### 1. 自动逻辑备份（CronJob）

`k8s/postgres-backup-cronjob.yaml` 每天凌晨 2 点对 `company_rag` 库执行 `pg_dump`（自定义格式），
写入独立备份 PVC `postgres-backup-pvc`（默认 10Gi），并自动清理超过 30 天的旧备份。

- 应用清单：`kubectl apply -f k8s/postgres-backup-cronjob.yaml`
- 备份产物挂载在备份 PVC 下，路径 `/backup/company_rag_<时间戳>.dump`
- 用途：抵御误删/表损坏/逻辑错误，可用于完整恢复

手工触发一次备份：
```bash
kubectl create job --from=cronjob/postgres-backup manual-backup
```

查看备份文件：
```bash
kubectl exec deploy/postgres -- ls -lh /backup
```

### 2. 手动逻辑备份

```bash
kubectl exec deploy/postgres -- pg_dump \
  -U postgres -d company_rag -F c -f /tmp/backup.dump

# 拷贝到本地
kubectl cp postgres:/tmp/backup.dump ./backup.dump
```

### 3. 基于 WAL 归档的 PITR（时间点恢复）

`k8s/postgres.yaml` 已开启 WAL 归档：
- 启动参数 `archive_mode=on`、`wal_level=replica`、`archive_command=cp %p /pgarchive/%f`
- 归档 WAL 写入独立 PVC `postgres-archive-pvc`（默认 20Gi），与主数据盘分离

**注意**：`archive_mode` 需在启动 `postgres` 部署后重启一次 Pod 才生效（首次以该清单创建时即生效）。

周期基础备份建议使用 `pg_basebackup`（物理备份，与 WAL 归档配套）：
```bash
# 在一个临时 pod 中执行，需 --no-password 与 PGPASSWORD
kubectl run pg-basebackup --image=pgvector/pgvector:pg16 --rm -it -- \
  bash -c 'export PGPASSWORD="$(kubectl get secret company-rag-secret -o jsonpath="{.data.POSTGRES_SUPERUSER_PASSWORD}" | base64 -d)"; \
  pg_basebackup -U postgres -h postgres -D /tmp/base -Ft -z -P'
```

**PITR 恢复要点**（恢复到基础备份 + WAL 回放到目标时间点）：
1. 准备一份基础备份（`pg_basebackup` 或完整数据卷快照）
2. 将基础备份解压到新的 `pgdata` 卷
3. 在数据目录放置归档的 WAL（从 `postgres-archive-pvc` 拷贝）
4. 在 `postgresql.conf`/启动参数设置 `restore_command=cp /pgarchive/%f %p` 与 `recovery_target_time`
5. 启动 postgres，完成后自动清理 `recovery.signal`

> 完整 PITR 演练步骤较多，如需要可参照 PostgreSQL 官方文档「Continuous Archiving and Point-in-Time Recovery（PITR）」。

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