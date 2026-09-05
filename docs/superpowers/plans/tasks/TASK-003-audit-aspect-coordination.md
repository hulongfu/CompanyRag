# TASK-003 AuditLogAspect 归属采集 + @AuditLog.async + 同步/异步分发

**模块:** `company-rag-common`
**依赖:** TASK-002（AuditLogService.record/recordAsync、AuditLogContext）

## 背景
`AuditLogAspect`（common/aspect，90行）现只调 `auditLogService.recordAuditLog(4参)`，且只取 `getCurrentUser()` 的 userId，**不采 ip、不行使 async 分发**。设计：
- §4.1/4.3：`@AuditLog` 注解新增 `async` 属性（默认 false，管理类同步）；数据类 `async=true`。
- §4.5：Aspect 只管一般操作（认证操作 LOGIN/LOGOUT 不经切面，见 TASK-004）。
- 归属采集 tenant/user/**ip** 拼 `AuditLogContext`，按 `async` 调 `record()`/`recordAsync()`。

`SecurityUser`（common，已核实）：含 `getTenantId()`(**Long**，当前租户，可 null)、`getUserId()`(Long)。实体 `tenantId` 为 String → `String.valueOf(tenantId)`（null 时用 `null`，但表 NOT NULL，见风险）。

---

- [ ] **Step 1: 写失败测试（TDD）**

**新建 `company-rag-common/src/test/java/com/company/rag/common/aspect/AuditLogAspectTest.java`**（设计 §8）：
- **归属采集正确**：mock `SecurityContextHolder` 认证主体为 `SecurityUser(userId=7, tenantId=3, tenantIds=[3])`；AOP 拦截 `@AuditLog` 方法 → verify `auditLogService.record(any(AuditLogContext.class))`，捕获 context，断言 `userId=7`、`tenantId="3"`（String 转换）、`actionType/targetType/targetId/detail` 与注解值一致。
- **async 分发**：标注 `@AuditLog(..., async=true)` → verify `auditLogService.recordAsync(...)`；`async` 缺省（false）→ verify `record(...)`。
- **ip 采集**：`RequestContextHolder` 伪造请求 `X-Forwarded-For` → context.ipAddress 正确；无请求时 ip 为 null 不抛。
- **user 为 null 跳过**：非 SecurityUser principal → 不调 record/recordAsync（沿用现有 `if (user==null) return result`）。
- **失败降级**：record 抛异常 → AOP 内部 catch，主方法结果正常返回、无异常泄漏。

> 注意：测试需为 common 引入 spring-test + aspectj（确认 common 既有依赖；若无，Step 2 加依赖）。

- [ ] **Step 2: 实现**

**1) `AuditLog` 注解（common/annotation）新增 `async`：**
```java
/**
 * 是否异步记录（数据类高吞吐为 true；管理类默认 false 同步）
 */
boolean async() default false;
```

**2) `AuditLogAspect`（common/aspect）改：**
```java
@Around("@annotation(auditLog)")
public Object around(ProceedingJoinPoint point, AuditLog auditLog) throws Throwable {
    Object result = point.proceed();
    try {
        SecurityUser user = getCurrentUser();
        if (user == null) return result;

        String targetId = parseSpel(auditLog.targetId(), point);
        String detail = parseSpel(auditLog.detail(), point);

        AuditLogContext ctx = AuditLogContext.builder()
            .actionType(auditLog.actionType())
            .targetType(auditLog.targetType())
            .targetId(targetId)
            .detail(detail)
            .userId(user.getUserId())
            .tenantId(user.getTenantId() != null ? String.valueOf(user.getTenantId()) : null)
            .ipAddress(resolveIp())
            .build();

        if (auditLog.async()) {
            auditLogService.recordAsync(ctx);
        } else {
            auditLogService.record(ctx);
        }
    } catch (Exception e) {
        log.warn("审计日志记录失败：{}", e.getMessage());
    }
    return result;
}
```
- `resolveIp()`：从 `RequestContextHolder` 取当前请求，读 `X-Forwarded-For`（首个）→ `X-Real-IP` → `getRemoteAddr()`；无请求返回 null。**不记录可信边界外细节**，仅存 IP 字符串供 admin 追溯。
- 保留现有 `getCurrentUser()` / `parseSpel()`（parseSpel 的 SpEL 上下文实现已有，复用）。
- targetType 已含默认 `""`，直接透传。

**3) 依赖确认**：common pom 需含 `spring-boot-starter-aop`、`spring-test`(test)、`aspectjweaver`。若缺，Step 2 补依赖。

- [ ] **Step 3: 验证（绿）**
```bash
mvn -q -pl company-rag-common test -Dtest=AuditLogAspectTest
```

- [ ] **Step 4: 提交**
- 提交信息：`feat(audit): AOP 采集归属并分发同步/异步（@AuditLog.async）`
- 提交范围：common 本任务文件。

---

## 风险点
- 【中】`tenantId` 类型转换：`SecurityUser.getTenantId()` 是 Long，`AuditLogContext.tenantId` 是 String。**治理规则8：用 `String.valueOf(...)`、null 兜底；严禁用 `toString()`**（null NPE）。表 `tenant_id NOT NULL`，若 `user.getTenantId()` 为 null（用户无当前租户）将违反约束——设计 §4.5 要求从 SecurityUser 取，此处若为 null 交给 record 的 catch 兜底（log.error 留痕），或在 TASK-004 明确 login 必然有 tenant 的前提。
- 【低】ip 采集依赖 RequestContextHolder 在线程内有请求；异步线程（recordAsync 后台）无法取，但 ip 在**入队前**已解析进 context，故不受影响——关键：Aspect 在调用侧解析 ip，而非异步线程内。
- 【低】AOP 只处理管理类/数据类 `@AuditLog` 标注；认证操作由 TASK-004 手动调用接管，避免双写。