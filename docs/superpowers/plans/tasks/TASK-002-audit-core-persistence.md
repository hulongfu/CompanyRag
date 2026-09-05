# TASK-002 落库核心：AuditLogContext + AuditLogService 扩展 + AuditLogAsyncWriter + AuditLogServiceImpl

**模块:** `company-rag-common`、`company-rag-tenant`
**依赖:** TASK-001（实体 @TableName、ignoreTable、表已建）

## 背景
设计 §4.2 将 `AuditLogService` 从「4 参幂等方法」扩展为「context 双轨」：`record(context)`（同步+REQUIRES_NEW）与 `recordAsync(context)`（入有界队列批量落库）。新增 `AuditLogContext`（common，DTO 载体），`AuditLogAsyncWriter`（tenant，异步批量），改 `AuditLogServiceImpl`（tenant，注入 AuditLogMapper 自营落库）。既有 `AuditLogService.recordAuditLog(4参)` 保留为兼容入口（TASK-004 的 TenantServiceImpl 仍会经它走）。

## Files
- Create: `company-rag-common/.../model/AuditLogContext.java`（DTO：actionType/targetType/targetId/detail + tenantId:String/userId:Long/ipAddress）
- Modify: `company-rag-common/.../service/AuditLogService.java`（加 `record(AuditLogContext)` + `recordAsync(AuditLogContext)`）
- Create: `company-rag-tenant/.../service/AuditLogAsyncWriter.java`（有界队列 + 后台批量 + @PreDestroy）
- Modify: `company-rag-tenant/.../service/AuditLogServiceImpl.java`（注入 AuditLogMapper 自营；recordAsync 委托 writer）
- Create: `company-rag-tenant/src/test/java/com/company/rag/tenant/service/AuditLogServiceImplTest.java`
- Create: `company-rag-tenant/src/test/java/com/company/rag/tenant/service/AuditLogAsyncWriterTest.java`

> ⚠️ 设计 §7.2 硬约束：**审计所有 SQL 一律带 `public.` 前缀**（写/查）。实体 `@TableName("public.audit_log")` 已在 TASK-001 完成，MyBatis-Plus 生成的 `INSERT INTO public.audit_log ...` 天然带前缀，规避 search_path 残留硬伤 2。

---

- [ ] **Step 1: 写失败测试（TDD）**

**`AuditLogServiceImplTest`：**
- 同步落库成功：`record(ctx)` → verify `auditLogMapper.insert(any(AuditLog.class))`（捕获实体，断言字段映射 `@TableName("public.audit_log")` + actionType/targetType/targetId/detail/tenantId/userId/ipAddress 正确）。
- 同步失败降级：`auditLogMapper.insert` 抛异常 → `record(ctx)` 不抛（内部 catch，log.warn），断言无异常泄漏。
- 兼容入口：`recordAuditLog(4参)` → 转成 `record(ctx)`（tenantId/userId 为 null 兜底）。
- `recordAsync(ctx)` → verify `auditLogAsyncWriter.offer(any(AuditLogContext.class))`。

**`AuditLogAsyncWriterTest`：**
- 入队后批量落库：enqueue 3 条 + 触发 flush → verify `auditLogMapper.insert*`（或批量）被调用、queue 清空。
- 背压丢弃：queue 容量填满后 enqueue → 丢弃 + 不抛（返回 false 或 warn），queue size 不超上限。
- `@PreDestroy` flush：shutdown 前队列残留 → 落库被调用。

> 注：若实现采用「批量 insertBatch」需先在测试断言明确批量语义；若为便于单测走逐条 `insert`（队内循环），则以实现选型为准，测试按最终 API 断言。**先写测试 → 实现 → 让测试绿。**

- [ ] **Step 2: 实现**

**1) `AuditLogContext`（common/model）**
```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class AuditLogContext {
    private String actionType;   // LOGIN / EXECUTE_TOOL / DATABASE_QUERY / DOWNLOAD / MCP_TOOL ...
    private String targetType;   // document / user / tenant / tool
    private String targetId;
    private String detail;       // 动作本体，不记输出/密钥
    private String tenantId;
    private Long userId;
    private String ipAddress;
}
```

**2) `AuditLogService`（common/service）** 接口新增两方法，保留既有 4 参方法：
```java
void record(AuditLogContext context);
void recordAsync(AuditLogContext context);
```

**3) `AuditLogAsyncWriter`（tenant/service）**
- 有界 `ArrayBlockingQueue<AuditLogContext>(1000)`（设计 §7.1，背压时 `offer` 失败丢弃 + `log.warn`）。
- 后台单线程（建议 `@Async` 或独立 `ExecutorService` + `ScheduledExecutorService`），每 100 条或每 500ms 批量落库（设计 §7.1）。用 `@Scheduled` 或循环 `drainTo`。
- 暴露 `boolean offer(AuditLogContext ctx)`。
- `@PreDestroy void shutdown()`：flush 队列残余再关线程。
- 落库经 `AuditLogService` 的同步 `record`（或直接经 Mapper 批量）——注意避免循环依赖（writer 注入 service 的同步实现 OK；service 注入 writer 做 recordAsync，依赖方向为 service→writer，writer 不反向依赖 service 的 async）。

**4) `AuditLogServiceImpl`（tenant/service）**
- 移除对 `TenantService` 的委托，注入 `AuditLogMapper` + `AuditLogAsyncWriter`。
```java
@Service @RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {
    private final AuditLogMapper auditLogMapper;
    private final AuditLogAsyncWriter auditLogAsyncWriter;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)   // 独立于主事务
    public void record(AuditLogContext ctx) {
        try {
            AuditLog log = new AuditLog();
            log.setTenantId(ctx.getTenantId());
            log.setUserId(ctx.getUserId());
            log.setActionType(ctx.getActionType());
            log.setTargetType(ctx.getTargetType());
            log.setTargetId(ctx.getTargetId());
            log.setDetail(ctx.getDetail());
            log.setIpAddress(ctx.getIpAddress());
            log.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(log);   // @TableName("public.audit_log")
        } catch (Exception e) {
            log.error("审计日志落库失败：{}", ctx, e);   // "审计的审计"兜底，不抛给主流程
        }
    }

    @Override
    public void recordAsync(AuditLogContext ctx) {
        if (!auditLogAsyncWriter.offer(ctx)) {
            log.warn("审计异步队列已满，丢弃记录：action={}", ctx.getActionType());
        }
    }

    @Override
    @Deprecated
    public void recordAuditLog(String actionType, String targetType, String targetId, String detail) {
        record(AuditLogContext.builder()   // 兼容入口：无归属信息
            .actionType(actionType).targetType(targetType).targetId(targetId).detail(detail).build());
    }
}
```
> 在 `record` 内 try/catch（设计 §7.1：审计失败仅 log，绝不抛给主流程）；`@Transactional(REQUIRES_NEW)` 独立于主事务。注意 `recordAuditLog` 现被 AuthController 等调用，deprecated 保留编译兼容，后续 TASK-004/005 改走 `record`/`recordAsync`。

- [ ] **Step 3: 验证（绿）**
```bash
mvn -q -pl company-rag-common,company-rag-tenant install -DskipTests
mvn -q -pl company-rag-tenant test -Dtest=AuditLogServiceImplTest,AuditLogAsyncWriterTest
mvn -q -pl company-rag-tenant test -Dtest=RlsIsolationTest,TenantAwareJdbcTemplateTest   # 多租户回归：确认 ignoreTable 未破坏既有隔离
```

- [ ] **Step 4: 提交**
- 提交信息：`feat(audit): 落库核心 AuditLogContext + 同步/异步双轨 + 有界队列批量写`
- 提交范围：common + tenant 本任务文件。

---

## 风险点
- 【中】`record` 依赖 `@Transactional(REQUIRES_NEW)` 在**同一线程**触发；异步队列后台线程落库时无主事务，`record` 的 REQUIRES_NEW 会新开事务——需确保 TenantSchemaInterceptor 在后台线程被注解处理（设计硬伤 2 已通过 public 前缀规避，不影响）。
- 【中】`@Async` 需确认 Spring 已开启 `@EnableAsync`；若未开启，writer 用自建 `ExecutorService`/`ScheduledExecutorService`，避免静默失效。
- 【中】`recordAuditLog` deprecated 后仍被调用（TASK-004 前 AuthController 走它），期间 tenantId/userId 为 null 会违反 NOT NULL——**TASK-004 需在改 AuthController 与 TenantServiceImpl 前尽快跟上**，避免中间态写入失败（record 内 catch 会吞掉，log.error 留痕，属接管可接受）。
- 【低】字段类型：`tenantId` String、`userId` Long，须在 Context 与实体间严格对齐，禁猜字段名。