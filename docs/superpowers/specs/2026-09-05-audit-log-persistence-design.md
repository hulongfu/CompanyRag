# 审计日志入库设计文档（分级双轨 + admin 只读查询）

**日期:** 2026-09-05
**状态:** 待用户审批
**定位:** 纵深防御的「检测与归因」层——与既有预防层（ExecuteTool 白名单、env.clear、租户隔离）正交互补。前者"拦住坏事"，本设计"坏事万一发生了能查出来是谁、何时、对哪个租户的数据干的"。

---

## 1. 背景与现状

### 1.1 审计框架骨架已存在，但落库为 TODO

项目已具备审计链路的骨架：

```
@AuditLog 注解 → AuditLogAspect(AOP) → AuditLogService → AuditLogServiceImpl → TenantService.recordAuditLog
```

但最后一环 `TenantServiceImpl.recordAuditLog`（`company-rag-tenant/.../TenantServiceImpl.java:407-410`）是 **TODO 空实现**，仅 `log.info` 打印、**不落库**：

```java
@Override
public void recordAuditLog(String actionType, String targetType, String targetId, String detail) {
    // TODO: 审计日志表创建后再实现，先留空
    log.info("审计日志：action={}, target={}, id={}, detail={}", actionType, targetType, targetId, detail);
}
```

### 1.2 现有资产（可复用）

- `AuditLog` 实体（`tenant/model`，`@TableName("audit_log")`，含 tenantId/userId/ipAddress/createdAt）
- `AuditLogMapper`（`tenant/mapper`，继承 `BaseMapper<AuditLog>`）
- `audit_log_ddl.sql`（建表脚本，幂等 `CREATE TABLE IF NOT EXISTS`，字段齐全，含两个索引）
- 已标注 `@AuditLog` 的控制器：`AuthController`（LOGIN/LOGOUT）、`UserController`（CREATE/UPDATE/DELETE_USER）、`TenantController`（CREATE/DELETE_TENANT）、`DocumentController`（DELETE_DOCUMENT）、`CacheManageController`（CLEAR_CACHE）
- `AuthController` 中还有两处手动 `tenantService.recordAuditLog(...)` 调用（LOGIN/LOGOUT）

### 1.3 暴露的问题

1. **信息断层**：AOP 目前只传 `actionType/targetType/targetId/detail`，实体/DDL 需要的 `tenant_id / user_id / ip_address` 取不到。
2. **落库缺失**：最后一环是 TODO，审计数据未持久化，"归因"无从谈起。

---

## 2. 目标与范围界定

**要做什么：**

- 把 `recordAuditLog` 的 TODO 补全为真实 DB 写入。
- 按操作特征分级处理（管理类同步、数据类异步批量）。
- 补全归属信息（tenant_id / user_id / ip_address）。
- 新增 admin 只读分页查询接口（按租户/用户/操作类型/时间过滤）。

**明确不做（YAGNI）：**

- ❌ 不做复杂审计前端界面（仅 REST 只读查询）。
- ❌ 不给租户开放审计查询（仅平台 admin）。
- ❌ 不做 detail 编辑/删除接口（审计只读不可变）。
- ❌ 不引入 ES/消息中间件做异步（轻量内存队列足够）。
- ❌ 不改 `audit_log` 表结构（现有字段已够用）。

---

## 3. 架构总览（分级双轨）

```
管理类(登录/登出、用户/租户/文档删除、缓存清空)     ── 同步直写 AuditLogMapper (REQUIRES_NEW)
数据类(RAG会话查询、文档上传/解析、ExecuteTool执行)  ── 有界队列 → 后台批量 insertBatch
                                      │
                                      ▼
                 public schema.audit_log （平台级数据，仅 admin 可读，不受 RLS）
```

### 3.1 分级依据

- **管理类**：低频、对"谁干了什么"最敏感，需即时、可靠、独立于主事务的证据 → 同步 `REQUIRES_NEW` 直写。
- **数据类**：高频（尤其 RAG 会话查询），逐条同步写会拖慢主流程并导致表高速增长 → 有界队列 + 后台批量落库。

---

## 4. 组件设计

### 4.1 组件拆分

| 组件 | 归属模块 | 动作 | 职责 |
|---|---|---|---|
| `AuditLog`（实体） | tenant/model | 已有，复用 | 字段已含 tenantId/userId/ip |
| `AuditLogMapper` | tenant/mapper | 已有，复用 | BaseMapper |
| `AuditLogContext`（新增 DTO） | common | **新增** | 载体：actionType/targetType/targetId/detail + 可选 tenantId/userId/ipAddress |
| `AuditLogService`（接口） | common | **改** | 暴露 `record(context)`（同步+REQUIRES_NEW）与 `recordAsync(context)`（入队） |
| `AuditLogAsyncWriter` | tenant | **新增** | 有界队列 + 后台批量 flush + `@PreDestroy` 兜底 |
| `AuditLogServiceImpl` | tenant | **改** | 注入 `AuditLogMapper`，实现落库；`recordAsync` 委托 AsyncWriter |
| `AuditLogAspect` | common | **改** | 采集 tenant/user/ip；按动作类型分发同步/异步 |
| `TenantServiceImpl.recordAuditLog` | tenant | **改** | 删除 TODO，改为真实调用（或委托 AsyncWriter） |
| `AuditLogQueryService` | tenant | **新** | admin 分页过滤查询 |
| `AuditLogController` | web | **新** | admin 只读查询 REST |

### 4.2 接口签名（common）

```java
public interface AuditLogService {
    // 同步写，事务 REQUIRES_NEW（独立于主事务）
    void record(AuditLogContext context);
    // 异步批量写（入有界队列）
    void recordAsync(AuditLogContext context);
}
```

`AuditLogContext` 置于 common，避免接口参数爆炸，并让 `ExecuteTool`（agent 模块→依赖 common）能引用。

### 4.3 分级判定机制（明确无歧义）

为避免"按 actionType 前缀推断"带来的隐式与维护负担，分级由**注解显式声明**：

- 在 `@AuditLog` 注解新增 **`async` 属性，默认 `false`（同步/管理类）**。
- 数据类操作在标注处显式 `@AuditLog(..., async = true)`，AOP 据此调用 `recordAsync`。
- 非注解的显式调用（如 ExecuteTool）直接选择 `record()` 或 `recordAsync()`。

初始分级映射（供实现计划落实）：
- 同步（async=false，默认）：LOGIN、LOGOUT、CREATE/UPDATE/DELETE_USER、CREATE/DELETE_TENANT、DELETE_DOCUMENT、CLEAR_CACHE
- 异步（async=true）：RAG 会话查询、文档上传/解析、EXECUTE_TOOL

### 4.4 ExecuteTool 接入（agent 模块）

- `ExecuteTool.executeCommand`（`ExecuteTool.java:101`）执行完成后调用 `auditLogService.recordAsync(...)`。
- **detail 只记命令本身，不记输出/环境变量**，避免脚本内容、密钥进入审计表。
- 依赖方向：agent → common（已存在），无反向依赖，架构干净。

---

## 5. 数据模型

沿用现有 `audit_log` 表，无需改结构：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL PK | 自增 |
| `tenant_id` | VARCHAR(32) NOT NULL | 归属租户（审计回答"对哪个租户"） |
| `user_id` | BIGINT NOT NULL | 操作者 |
| `action_type` | VARCHAR(32) NOT NULL | LOGIN / DELETE_DOCUMENT / EXECUTE_TOOL 等 |
| `target_type` | VARCHAR(32) | document / user / tenant / tool |
| `target_id` | VARCHAR(64) | 目标 ID |
| `detail` | TEXT | 详情（含 ExecuteTool 命令文本） |
| `ip_address` | VARCHAR(45) | 客户端 IP |
| `created_at` | TIMESTAMP | 默认 NOW() |

已建索引：`(tenant_id, action_type, created_at DESC)`、`(created_at DESC)`。

---

## 6. admin 只读查询接口

**`GET /api/admin/audit-logs`**

- 请求参数：`tenantId`（可选）、`userId`（可选）、`actionType`（可选）、`startTime`（可选）、`endTime`（可选）、`page`（默认 1）、`pageSize`（默认 20）
- 响应：`R<Page<AuditLog>>` 统一格式（分页，含总条数）
- 权限：仅 `ROLE_ADMIN`，其余角色 403（走既有 Spring Security 授权链）

---

## 7. 可靠性 / 安全 / 隔离

### 7.1 可靠性（分级）

**管理类（同步 REQUIRES_NEW）：**
- `@Transactional(propagation = REQUIRES_NEW)`，审计写入独立于主事务——主流程回滚不影响审计留存。
- AOP 环绕层自带 try/catch，审计失败仅 `log.warn`，**绝不抛给主流程**。

**数据类（异步批量）：**
- 有界 `BlockingQueue`（建议容量 1000），背压时丢弃并 `log.warn`（避免拖垮 RAG 主流程）。
- 后台单线程定时/按阈值刷库（每 100 条或每 500ms `batchInsert`）。
- `@PreDestroy` flush 残余，JVM 关闭不丢队列数据。

### 7.2 多租户隔离——关键风险点

`audit_log` 在 **public schema、平台级**，与 `TenantAwareJdbcTemplate` 的 `vector_store` 同理：它是跨租户追溯数据，**不受 RLS、也不应被 MyBatis-Plus 多租户拦截器自动追加 `tenant_id` 条件**。

⚠️ **必须**：在 MyBatis-Plus 多租户拦截器中对 `audit_log` 表做**豁免**，否则租户上下文下任何审计相关查询会被误加 `tenant_id` 过滤，导致：
- admin 跨租户查询被误截断；
- 数据类批量写时带上当前调用的 tenant_id 污染。

此条需在 boundaries / iron-rules 中显式记录。

### 7.3 安全

- admin 接口走既有 Spring Security，`ROLE_ADMIN`。
- **ExecuteTool detail 只记命令，不记输出/环境变量**。
- 审计只读不可变，无编辑/删除接口。

---

## 8. 测试策略

遵循项目测试规范（正常 / 边界 / 异常）：

- `AuditLogServiceImplTest`：同步落库成功 / 失败降级（mock Mapper）；REQUIRES_NEW 独立事务验证。
- `AuditLogAsyncWriterTest`：队列背压丢弃、批量刷库、`@PreDestroy` flush。
- `AuditLogAspectTest`：归属信息（tenant/user/ip）补齐正确。
- `ExecuteToolTest`（扩展）：执行后调用 `recordAsync`，且命令不外泄（不记输出/密钥）。
- admin 查询 Controller 测试：过滤分页正确；非 admin 403。

---

## 9. 风险点小结

1. **多租户拦截器豁免 audit_log**：否则租户/平台查询被误过滤（7.2）。
2. **失败留痕**：审计自身失败也应有 `log.error` 兜底，形成"审计的审计"。
3. **detail 敏感性**：命令文本可能敏感，仅 admin 可查。
4. **队列背压**：需有界 + 丢弃告警，避免内存溢出拖垮服务。