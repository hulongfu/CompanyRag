# TASK-004 方案A认证事件审计：AuthController + TenantServiceImpl 委托

**模块:** `company-rag-web`、`company-rag-tenant`
**依赖:** TASK-002（AuditLogService.record + AuditLogContext）

## 背景
设计 §4.5（方案 A）：LOGIN/LOGOUT **不经切面**（登录时 SecurityContext 未建立、切面取不到用户），由 `AuthController` 手动调 `auditLogService.record(...)` 写同步审计；归属从 `SecurityUser` 取，不依赖 TenantContext。

已核实现状：
- `AuthController`（web）注入 `TenantService`，L74-75（login）/L80-81（logout）手动调 `tenantService.recordAuditLog("LOGIN"/"LOGOUT","USER",userId,detail)`，**无 @AuditLog 注解**。
- login 中 `SecurityUser` 从 `authentication.getPrincipal()` 取（含 `getTenantId()` Long / `getTenantIds()`）；logout 从 `SecurityContextHolder` 取。
- `TenantServiceImpl.recordAuditLog`（L407-410）TODO 空实现，仅 log.info。

改动：AuthController 改注入 `AuditLogService`，调 `record(ctx)`（同步，方案A）；`TenantServiceImpl.recordAuditLog` 委托 `AuditLogServiceImpl`（4参兼容入口，TASK-002 已实现）。

> 设计 §4.5「移除 @AuditLog」：AuthController 现本就无 @AuditLog 注解，故无需移除，仅需将 `tenantService.recordAuditLog` 换成 `auditLogService.record(ctx)`。

---

- [ ] **Step 1: 写失败测试（TDD）**

**新建/扩展 `company-rag-web/src/test/java/com/company/rag/web/controller/AuthControllerTest.java`**（设计 §8）：
- **login 审计**：mock `AuthenticationManager.authenticate` 返回 principal=`SecurityUser(userId=7, tenantId=3, tenantIds=[3])`；调 login → verify `auditLogService.record(any(AuditLogContext.class))`，捕获断言 `actionType=LOGIN`、`targetType=USER`、`userId=7`、`tenantId="3"`、`detail` 含用户名。
- **logout 审计**：mock `SecurityContextHolder` 认证主体 SecurityUser → 调 logout → verify `record`，actionType=LOGOUT。
- **兼容入口**：`TenantServiceImpl.recordAuditLog(4参)` → 委托后 verify `auditLogService.recordAuditLog(4参)`（或 record）被调用（无归属者用默认兜底）。

> 若 tenant 模块需对 `TenantServiceImpl.recordAuditLog` 委托写单测，可新建 `TenantServiceImplAuditTest` 或并入既有 TenantService 测试。

- [ ] **Step 2: 实现**

**1) `AuthController`（web）改注入：**
```java
// 移除/保留 tenantService 视其余用法而定；审计改走
private final AuditLogService auditLogService;   // @RequiredArgsConstructor 注入
```
- **login**（替换 L74-75）：
```java
SecurityUser su = securityUser;                     // 已从 principal 取到
auditLogService.record(AuditLogContext.builder()
    .actionType("LOGIN")
    .targetType("USER")
    .targetId(String.valueOf(securityUser.getUserId()))
    .detail("用户登录成功：" + request.getUsername())
    .userId(securityUser.getUserId())
    .tenantId(securityUser.getTenantId() != null ? String.valueOf(securityUser.getTenantId()) : null)
    .build());
```
- **logout**（替换 L80-81）：从 `SecurityContextHolder` 取的 `SecurityUser`，同类 `record`（actionType=LOGOUT）。
- 审计调用须在主要业务逻辑后、返回前；审计失败由 record 内部 catch，不影响响应。

**2) `TenantServiceImpl.recordAuditLog`（tenant，L407）改：**
```java
// TODO 删除，委托 AuditLogServiceImpl 落库（4参兼容入口）
private final AuditLogService auditLogService;   // 注入
@Override
public void recordAuditLog(String actionType, String targetType, String targetId, String detail) {
    auditLogService.recordAuditLog(actionType, targetType, targetId, detail);
}
```
> 该 4 参规范仍被其它显式调用方使用，保留语义（无归属兜底），由 TASK-002 的 deprecated `recordAuditLog` 转换。

- [ ] **Step 3: 验证（绿）**
```bash
mvn -q -pl company-rag-common,company-rag-tenant install -DskipTests
mvn -q -pl company-rag-web test -Dtest=AuthControllerTest
mvn -q -pl company-rag-tenant test -Dtest=TenantServiceImplAuditTest,RlsIsolationTest
```

- [ ] **Step 4: 提交**
- 提交信息：`feat(audit): 方案A认证事件经 AuditLogService 记录（LOGIN/LOGOUT 同步）`
- 提交范围：web AuthController + tenant TenantServiceImpl。

---

## 风险点
- 【中】**login 的 tenantId**：设计 §5 `tenant_id NOT NULL`，但方案A从 `SecurityUser.getTenantId()` 取。若该值 null（登录用户无 tenant）会违反约束。需确认**登录流程是否必然填充 tenantId**。若否，需在 login 显式报错或改用 `tenantIds.get(0)`（与 AuthController L66 的 `currentTenantId` 现有逻辑一致），或接受 record catch 兜底（审计丢失该条）。**提交前需向用户标明此边界**。
- 【中】login 时 `record` REQUIRES_NEW：在认证事务内新开事务独立提交，登录取证不受登录事务回滚影响（设计 §7.1）。
- 【低】AuthController 保留 `tenantService` 其它用途（若 `loadSecurityUserById` 等还需用）则两 bean 都注入，勿误删。