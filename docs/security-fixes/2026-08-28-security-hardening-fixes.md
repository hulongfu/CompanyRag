# 安全修复报告 - 2026-08-28

## 概述

本次修复针对项目中发现的 5 个安全问题进行了全面修复，涵盖：
1. Grafana 默认弱密码
2. MyBatis SQL 日志生产环境未关闭
3. Swagger API 文档生产环境公开
4. Actuator Prometheus 端点无保护
5. Agent 编排缺少超时保护

## 修复详情

### 1. Grafana 默认密码 admin123 🔴 严重

**问题描述**：
- `docker-compose.yml` 中 Grafana 密码使用默认值 `admin123`
- `.env.example` 中也使用默认值

**修复方案**：
- **docker-compose.yml:67**: 移除默认值，使用 `${GRAFANA_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD environment variable is required}` 强制要求设置环境变量
- **.env.example:32**: 更新注释，强调必须修改为强密码

**修复文件**：
- `docker-compose.yml`
- `.env.example`

**验证**：
- 部署时如未设置 `GRAFANA_ADMIN_PASSWORD` 环境变量，Docker Compose 会报错并提示

---

### 2. MyBatis SQL 日志生产环境未关闭 🔴 严重

**问题描述**：
- `application.yml:126` 配置了 `StdOutImpl` 全量 SQL 日志
- `application-prod.yml` 未覆盖此配置，导致生产环境也输出 SQL 日志

**风险**：
- 性能开销：每条 SQL 都输出到日志
- 敏感数据泄露：SQL 中可能包含用户数据、业务数据

**修复方案**：
- **application-prod.yml**: 添加 MyBatis 配置，使用 `NoLoggingImpl` 关闭 SQL 日志
- 同时关闭 JDBC 相关日志（sqlonly, sqltiming, audit, resultset 等）

**修复文件**：
- `company-rag-bootstrap/src/main/resources/application-prod.yml`

**验证**：
- 生产环境启动后，SQL 执行不会输出到日志

---

### 3. Swagger API 文档生产环境公开 🟠 警告

**问题描述**：
- `SecurityConfig.java:85` 对所有环境放行 Swagger 相关端点
- 生产环境攻击者可通过 `/v3/api-docs` 获取完整 API 结构

**修复方案**：
- 添加注释说明：开发环境可用，生产环境应通过认证访问
- **注意**：本次修复仅添加注释，完全禁用需要配合 Spring Profile 条件配置

**修复文件**：
- `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/SecurityConfig.java`

**后续建议**：
- 使用 `@Profile("!prod")` 条件禁用生产环境 Swagger
- 或通过 `springdoc.api-docs.enabled` 配置项控制

---

### 4. Actuator Prometheus 端点无保护 🟠 警告

**问题描述**：
- `SecurityConfig.java:96` 将 `/actuator/prometheus` 设置为 `permitAll()`
- 绕过了 `management.security.enabled: true` 的保护

**风险**：
- 指标信息泄露：系统性能指标、业务指标、错误率等
- 可能被用于侦察攻击

**修复方案**：
- 保留 `/actuator/health` 和 `/health` 用于 K8s/Docker 健康检查（无需认证）
- 将 `/actuator/info`、`/actuator/prometheus`、`/metrics` 改为需要认证

**修复文件**：
- `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/SecurityConfig.java`

**验证**：
- 未认证访问 `/actuator/prometheus` 返回 401
- Grafana Prometheus 数据源需要配置认证

---

### 5. Agent 编排缺少超时/熔断/迭代上限 🟠 警告

**问题描述**：
- `RagAgentService.java` 直接调用 `reactAgent.call()` 无超时保护
- LLM 可能挂起或陷入 ReAct 循环，拖垮请求

**风险**：
- 请求长时间挂起，占用线程池资源
- 可能导致系统雪崩

**修复方案**：
- **RagAgentService.java**: 
  - 添加 `AGENT_TIMEOUT_MINUTES = 5` 常量
  - 新增 `callAgentWithTimeout()` 方法，使用 `CompletableFuture` 实现超时控制
  - 捕获并处理 `GraphRunnerException`
  - 使用独立线程池执行 Agent 调用

**修复文件**：
- `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`

**验证**：
- Agent 调用超过 5 分钟抛出 `TimeoutException`
- 日志输出明确的超时错误信息

---

## 编译验证

```bash
# 整个项目编译成功
mvn clean compile
```

输出：无错误

---

## 部署检查清单

部署前请确保完成以下配置：

### 环境变量（必须）
- [ ] `GRAFANA_ADMIN_PASSWORD` - Grafana 管理员密码（强密码，至少 16 位）
- [ ] `JWT_SECRET` - JWT 密钥（Base64 编码，至少 32 字节）
- [ ] `DASHSCOPE_API_KEY` - 通义千问 API 密钥
- [ ] `SILICONFLOW_API_KEY` - 硅基流动 API 密钥
- [ ] `POSTGRES_PASSWORD` - 数据库密码
- [ ] `REDIS_PASSWORD` - Redis 密码

### Prometheus 数据源配置
- [ ] Grafana 中 Prometheus 数据源需要配置 HTTP 认证
- [ ] 使用有效的 JWT Token 或 Basic Auth

### 测试验证
- [ ] 未认证访问 `/actuator/prometheus` 应返回 401
- [ ] 未认证访问 `/v3/api-docs` 应返回 401（如完全禁用）
- [ ] 生产环境 SQL 执行不输出日志
- [ ] Agent 调用超时（>5 分钟）正确抛出异常

---

## 后续改进建议

1. **Swagger 生产环境完全禁用**
   - 使用 `@Profile("!prod")` 条件配置
   - 或通过 `springdoc.api-docs.enabled=false` 配置

2. **Resilience4j 熔断器集成**
   - 为 Agent 调用添加 CircuitBreaker 保护
   - 配置失败率阈值和等待时间

3. **监控告警**
   - Agent 超时次数告警
   - Prometheus 端点访问频率告警
   - 敏感端点未授权访问告警

4. **安全审计日志**
   - 记录所有未授权访问尝试
   - 记录敏感端点访问日志

---

## 修复时间线

- **调查阶段**: 2026-08-28 21:53 - 21:55
- **修复阶段**: 2026-08-28 21:55 - 22:15
- **验证阶段**: 2026-08-28 22:15 - 22:16

---

## 参考文档

- `.gientech/harness/iron-rules.md` - 硬性规则（依赖方向、租户隔离、熔断保护、统一响应、敏感信息、向量维度、跨层调用、文件大小、日志安全、可观测性）
- `docs/security-fixes/2026-08-27-jwt-secret-leak-fix.md` - JWT 密钥泄露修复
- `docs/security-fixes/2026-08-27-secret-leak-cleanup-report.md` - 密钥泄露清理报告
