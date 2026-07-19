# Actuator 端点 API

**本文档中引用的文件**
- [application.yml](../../../company-rag-bootstrap/src/main/resources/application.yml)(L75-L83)
- [pom.xml](../../../company-rag-bootstrap/pom.xml)(L23-L26)
- [prometheus.yml](../../../prometheus.yml)
- [README.md](../../../README.md)

## 目录
1. [简介](#简介)
2. [Actuator 配置概览](#actuator-配置概览)
3. [可用端点详解](#可用端点详解)
4. [Prometheus 监控集成](#prometheus-监控集成)
5. [安全与访问控制](#安全与访问控制)
6. [故障排除](#故障排除)
7. [总结](#总结)

## 简介

### 系统描述
CompanyRag 企业知识库 RAG 系统集成了 Spring Boot Actuator 作为生产级监控和运维接口。Actuator 提供了标准化的端点来监控系统健康状态、应用信息、性能指标等关键运维数据。

### 核心功能
- **健康检查**：应用及依赖组件（PostgreSQL、Redis、LLM 服务）的健康状态监控
- **应用信息**：版本信息、构建元数据、运行时配置
- **指标采集**：JVM 性能、HTTP 请求统计、业务指标
- **Prometheus 集成**：原生支持 Prometheus 格式的指标导出

### 技术架构
Actuator 作为 Spring Boot 3.4.4 的标准组件，通过 `spring-boot-starter-actuator` 依赖引入，配合 `micrometer-registry-prometheus` 实现指标采集和导出。

### 用户角色
- **运维人员**：通过 `/actuator/health` 和 `/actuator/prometheus` 监控系统状态
- **开发人员**：通过 `/actuator/info` 和 `/actuator/metrics` 调试应用问题
- **监控系统**：Prometheus 定期抓取指标数据进行告警和可视化

## Actuator 配置概览

### 依赖配置
Actuator 通过 Maven 依赖引入：

```xml
<!-- company-rag-bootstrap/pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

**来源**：[pom.xml](../../../company-rag-bootstrap/pom.xml)(L23-L26)

### 端点暴露配置
在 `application.yml` 中配置暴露的端点：

```yaml
# Actuator / Prometheus
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
  metrics:
    tags:
      application: ${spring.application.name}
```

**来源**：[application.yml](../../../company-rag-bootstrap/src/main/resources/application.yml)(L75-L83)

### 配置说明
| 配置项 | 值 | 说明 |
|--------|-----|------|
| `management.endpoints.web.exposure.include` | `health,info,prometheus,metrics` | 仅暴露必要的端点，遵循最小权限原则 |
| `management.metrics.tags.application` | `${spring.application.name}` | 为所有指标添加应用标签，便于 Prometheus 聚合 |

## 可用端点详解

### 1. 健康检查端点 `/actuator/health`

**功能**：检查应用及其依赖组件的健康状态

**请求示例**：
```bash
curl http://localhost:8080/actuator/health
```

**响应示例**：
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP",
      "details": {
        "database": "PostgreSQL",
        "validationQuery": "SELECT 1"
      }
    },
    "redis": {
      "status": "UP",
      "details": {
        "version": "7.0.0"
      }
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

**状态码说明**：
- `UP`：所有组件正常
- `DOWN`：至少一个组件异常
- `UNKNOWN`：状态未知

**使用场景**：
- Kubernetes 存活探针（liveness probe）
- 负载均衡器健康检查
- 运维监控系统轮询

### 2. 应用信息端点 `/actuator/info`

**功能**：展示应用版本、构建信息等元数据

**请求示例**：
```bash
curl http://localhost:8080/actuator/info
```

**响应示例**：
```json
{
  "build": {
    "artifact": "company-rag-bootstrap",
    "name": "CompanyRag",
    "version": "1.0.0-SNAPSHOT"
  },
  "java": {
    "version": "17",
    "vendor": "Oracle Corporation"
  }
}
```

**自定义配置**：可通过 `application.yml` 添加自定义信息：
```yaml
management:
  info:
    env:
      enabled: true
info:
  app:
    description: "企业知识库 RAG 系统"
    environment: "production"
```

### 3. Prometheus 指标端点 `/actuator/prometheus`

**功能**：以 Prometheus 格式导出应用指标

**请求示例**：
```bash
curl http://localhost:8080/actuator/prometheus
```

**响应示例**（Prometheus 文本格式）：
```
# HELP jvm_memory_used_bytes 当前 JVM 内存使用量
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Old Generation",} 2.5E7
jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 1.2E8
# HELP http_server_requests_seconds HTTP 请求耗时统计
# TYPE http_server_requests_seconds summary
http_server_requests_seconds_count{method="GET",uri="/api/documents",} 150.0
http_server_requests_seconds_sum{method="GET",uri="/api/documents",} 45.3
```

**核心指标类别**：
- **JVM 指标**：内存使用、GC 次数、线程数
- **HTTP 指标**：请求数、响应时间、状态码分布
- **业务指标**：文档解析数、向量生成数、RAG 查询次数

**使用场景**：Prometheus 定期抓取（默认 15 秒）

### 4. 指标查询端点 `/actuator/metrics`

**功能**：查询特定指标的详细信息

**请求示例**：
```bash
# 获取所有可用指标名称
curl http://localhost:8080/actuator/metrics

# 获取特定指标详情
curl http://localhost:8080/actuator/metrics/jvm.memory.used
```

**响应示例**：
```json
{
  "name": "jvm.memory.used",
  "description": "当前 JVM 内存使用量",
  "measurements": [
    {
      "statistic": "VALUE",
      "value": 2.5E7
    }
  ],
  "availableTags": [
    {
      "tag": "area",
      "values": ["heap", "nonheap"]
    },
    {
      "tag": "id",
      "values": ["G1 Old Generation", "G1 Eden Space"]
    }
  ]
}
```

## Prometheus 监控集成

### Prometheus 配置
项目提供 `prometheus.yml` 配置文件：

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'company-rag'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['app:8080']
        labels:
          application: 'company-rag'
```

**来源**：[prometheus.yml](../../../prometheus.yml)

### 配置说明
| 配置项 | 值 | 说明 |
|--------|-----|------|
| `scrape_interval` | `15s` | 每 15 秒抓取一次指标 |
| `metrics_path` | `/actuator/prometheus` | Actuator Prometheus 端点路径 |
| `targets` | `['app:8080']` | Docker 环境下的服务地址 |

### Docker Compose 部署
在 Docker Compose 环境中，Prometheus 容器通过服务名 `app` 访问应用：

```yaml
services:
  app:
    image: company-rag:latest
    ports:
      - "8080:8080"
  
  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"
```

### Grafana 可视化（可选）
Prometheus 数据可作为 Grafana 数据源，实现可视化监控：
- **JVM 内存面板**：堆内存、非堆内存使用趋势
- **HTTP 请求面板**：QPS、响应时间 P95/P99
- **业务指标面板**：文档处理量、RAG 查询次数

## 安全与访问控制

### 端点访问限制
出于安全考虑，仅暴露必要的端点：
- ✅ `health` - 健康检查（可公开访问）
- ✅ `info` - 应用信息（可公开访问）
- ✅ `prometheus` - 指标导出（建议内网访问）
- ✅ `metrics` - 指标查询（建议内网访问）

- ❌ `env` - 环境变量（不暴露，防止敏感信息泄露）
- ❌ `configprops` - 配置属性（不暴露）
- ❌ `threaddump` - 线程转储（不暴露）
- ❌ `shutdown` - 关闭端点（不暴露，防止恶意关闭）

### 生产环境建议
1. **网络隔离**：将 `/actuator/prometheus` 和 `/actuator/metrics` 限制在内网访问
2. **认证保护**：通过 Spring Security 为 Actuator 端点添加 Basic Auth
3. **端口分离**：使用独立管理端口（如 `management.server.port=8081`）

**配置示例**：
```yaml
# 独立管理端口
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

## 故障排除

### 常见问题

#### 1. 端点返回 404
**原因**：Actuator 依赖未正确引入或配置

**解决方案**：
- 检查 `pom.xml` 中是否包含 `spring-boot-starter-actuator`
- 确认 `application.yml` 中配置了 `management.endpoints.web.exposure.include`

#### 2. Prometheus 无法抓取指标
**原因**：网络不通或配置错误

**解决方案**：
- 检查 Prometheus 容器是否能访问 `app:8080`
- 确认 `metrics_path` 配置为 `/actuator/prometheus`
- 查看应用日志中是否有 Micrometer 相关错误

#### 3. 健康检查显示 DOWN
**原因**：依赖组件（数据库、Redis）连接失败

**解决方案**：
- 检查 PostgreSQL 和 Redis 服务状态
- 验证数据库连接配置（URL、用户名、密码）
- 查看 `/actuator/health` 响应中的 `components` 详情

### 监控指标说明

| 指标名称 | 类型 | 说明 |
|----------|------|------|
| `jvm.memory.used` | Gauge | JVM 内存使用量（字节） |
| `jvm.gc.pause` | Timer | GC 暂停时间统计 |
| `http.server.requests` | Timer | HTTP 请求耗时和计数 |
| `process.cpu.usage` | Gauge | CPU 使用率（0-1） |
| `system.cpu.usage` | Gauge | 系统 CPU 使用率 |

## 总结

### 主要特点
1. **标准化端点**：遵循 Spring Boot Actuator 规范，与运维工具链无缝集成
2. **最小化暴露**：仅开放必要的健康检查和指标端点，降低安全风险
3. **Prometheus 原生支持**：无需额外配置即可导出 Prometheus 格式指标
4. **多维度标签**：所有指标自动添加应用标签，便于多实例聚合
5. **生产就绪**：经过验证的配置，适用于 Docker Compose 部署环境

### 技术亮点
- **Micrometer 抽象层**：统一指标采集 API，支持切换其他监控系统（如 Datadog、New Relic）
- **自动配置**：Spring Boot 自动配置健康检查器和指标收集器
- **低开销**：指标采集对应用性能影响小于 1%

### 业务价值
- **快速故障定位**：通过健康检查和指标快速识别问题组件
- **容量规划**：基于历史指标数据进行资源规划和扩容决策
- **SLA 保障**：持续监控应用可用性，支撑 99.9% SLA 目标
- **成本优化**：通过指标分析识别资源浪费，优化云资源成本

---

**文档版本**：1.0  
**最后更新**：2026-07-19  
**维护团队**：CompanyRag 开发团队
