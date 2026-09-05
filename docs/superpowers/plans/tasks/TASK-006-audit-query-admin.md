# TASK-006 admin 只读查询：AuditLogQueryService + AuditLogController + 前端页面

**模块:** `company-rag-web`、`company-rag-tenant`
**依赖:** TASK-001（ignoreTable+表）、TASK-002（实体可查）

## 背景
设计 §6/§1.2：admin 平台级只读分页查询，按租户/用户/操作类型/时间过滤。仅 ROLE_ADMIN 可访问。不加写接口（审计只读不可变）。

API 契约（design §6）：
- `GET /api/admin/audit-logs`
- 参数（均可选）：`tenantId`、`userId`、`actionType`、`startTime`、`endTime`、`page`（默认1）、`pageSize`（默认20）
- 响应：`R<Page<AuditLog>>`（分页，含总条数）

组件：`AuditLogQueryService`（tenant 新增）、`AuditLogController`（web 新增）、`audit-log.html`（web 模板新增）、`index.html`（web 模板改，admin 头部加"日志"按钮）。

> 关键：查询表是平台级 `audit_log`（public），必须依赖 TASK-001 的 `ignoreTable` 豁免，否则 TenantLine 自动给查询追加 `tenant_id=?`（当前用户租户）截断跨租户视图——admin 应能看到所有租户。豁免后 MyBatis-Plus 分页不受租户行级拦截。

---

- [ ] **Step 1: 写失败测试（TDD）**

**`company-rag-tenant/src/test/.../service/AuditLogQueryServiceTest.java`**（设计 §8）：
- **无过滤分页**：query(page,pageSize) → 返回 `Page`，verify `auditLogMapper.selectPage(any(Page.class), any(Wrapper.class))`，且 **条件 wrapper 为空**（全量）。
- **单一过滤**：传 `tenantId`/`userId`/`actionType` 各自 → verify wrapper 含对应 `eq` 条件。
- **时间范围**：传 `startTime`+`endTime` → wrapper 含 `ge`(start)/`le`(end) on createdAt。
- **组合过滤**：全部条件 → wrapper 同时含所有 `eq`/`ge`/`le`。
- **null 过滤被忽略**：仅传 page → 不出意外条件。
- **`@TableName("public.audit_log")` 生效**（反射或集成断言查询目标为 public.audit_log，防止回退裸表名）。

**`company-rag-web/src/test/.../controller/AuditLogControllerTest.java`**：
- 参数透传：GET `/api/admin/audit-logs?tenantId=1&userId=2&actionType=LOGIN&page=1&pageSize=20` → verify `auditLogQueryService.query(...)` 收到对应参数、返回 `R<Page<AuditLog>>`。
- 默认 page/pageSize=1/20（缺参）。
- 403：无 ROLE_ADMIN 主体 → 拒绝访问（`@PreAuthorize("hasRole('ADMIN')")` 生效）。

- [ ] **Step 2: 实现**

**1) `AuditLogQueryService`（tenant/service 新增）**
```java
@Service @RequiredArgsConstructor
public class AuditLogQueryService {
    private final AuditLogMapper auditLogMapper;

    public Page<AuditLog> query(String tenantId, Long userId, String actionType,
                                LocalDateTime startTime, LocalDateTime endTime,
                                long page, long pageSize) {
        Page<AuditLog> p = new Page<>(page, pageSize);
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        qw.eq(tenantId != null && !tenantId.isBlank(), AuditLog::getTenantId, tenantId)
          .eq(userId != null, AuditLog::getUserId, userId)
          .eq(actionType != null && !actionType.isBlank(), AuditLog::getActionType, actionType)
          .ge(startTime != null, AuditLog::getCreatedAt, startTime)
          .le(endTime != null, AuditLog::getCreatedAt, endTime)
          .orderByDesc(AuditLog::getCreatedAt);
        return auditLogMapper.selectPage(p, qw);
    }
}
```
> 依赖 TASK-001：`AuditLog` 实体 `@TableName("public.audit_log")` + `ignoreTable` 豁免。若查询被 TenantLine 追加 tenant_id，此服务将只见当前租户——**验证 Step1 组合过滤用例须断言 wrapper 不含 tenant_id 自动条件（豁免生效）**。

**2) `AuditLogController`（web 新增）**
```java
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {
    private final AuditLogQueryService auditLogQueryService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")   // 仅平台管理员
    public R<Page<AuditLog>> query(
            @RequestParam(required=false) String tenantId,
            @RequestParam(required=false) Long userId,
            @RequestParam(required=false) String actionType,
            @RequestParam(required=false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required=false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue="1") long page,
            @RequestParam(defaultValue="20") long pageSize) {
        return R.ok(auditLogQueryService.query(tenantId, userId, actionType, startTime, endTime, page, pageSize));
    }
}
```
> `@PreAuthorize("hasRole('ADMIN')")` 需确认 `@EnableGlobalMethodSecurity`/`@EnableMethodSecurity` 已开启（log 若未开启需在 SecurityConfig 加）。**引用 `Page`/`R`/`AuditLog` 分别来自 mybatis-plus-extension / common / tenant 实体——web 需确认已依赖 tenant。**

**3) `audit-log.html`（web/templates 新增）**（design §页面）
- 复用 `admin.html` Vue3 + Element Plus + 深色头部。
- 查询区：tenantId/userId/actionType 输入 + startTime/endTime `el-date-picker` + 查询/重置。
- 列表区：`el-table`（createdAt/userId/tenantId/actionType/targetType/targetId/detail截断/ipAddress），`actionType` 用 `el-tag` 着色。
- 分页：`el-pagination`（page/pageSize 绑定接口）。
- 详情：行点击弹 `el-drawer`（完整字段+只读标注）。
- 数据加载：`onMounted` GET `/api/admin/audit-logs?page=1&pageSize=20`，`Authorization: Bearer <token>`（login.html 存的 token）。
- 头部"← 返回首页"（同 `admin.html:24 goHome`）。
- 参考：既有 `admin.html`（登录后跳转、axios 封装、token 头）——**编写前读 `admin.html` 对齐风格与 axios 拦截器**。

**4) `index.html`（web/templates 改）**：头部加"📋 日志"按钮，`v-show/v-if="role==='admin'"`，点击跳 `audit-log.html`。参考既有 admin 头部按钮样式。

- [ ] **Step 3: 验证（绿）**
```bash
mvn -q -pl company-rag-common,company-rag-tenant install -DskipTests
mvn -q -pl company-rag-web test -Dtest=AuditLogControllerTest
mvn -q -pl company-rag-tenant test -Dtest=AuditLogQueryServiceTest,RlsIsolationTest,TenantAwareJdbcTemplateTest
# 前端页面：手动启动后浏览器验证（无法单测），至少 404/资源加载不受影响
```

- [ ] **Step 4: 提交**
- 提交信息：`feat(audit): admin 只读分页查询接口 + 日志查询页`
- 提交范围：tenant AuditLogQueryService + web AuditLogController + audit-log.html + index.html + 测试。

---

## 风险点
- 【高】**豁免缺失导致跨租户查询被截断**：若 TASK-001 `ignoreTable` 未加 audit_log，`selectPage` 时 TenantLine 自动加当前用户 tenant_id 条件，admin 只能看到自己租户、看不到全量。必须依赖 TASK-001，Step1 组合过滤用例显式断言 wrapper **无自动 tenant_id 条件**。
- 【中】**web 依赖**：`AuditLogController` 引用 tenant 的 `AuditLogQueryService`/`AuditLog` 与 common 的 `R`——需确认 web pom 已依赖 tenant 模块；若未依赖，需新增依赖（本任务 Step2 依赖检查）。
- 【中】**方法级安全开启**：`@PreAuthorize` 需 `@EnableMethodSecurity`/`@EnableGlobalMethodSecurity`。若 SecurityConfig 未开，admin 接口将无 403 保护——本任务需核查并补充开启（若全局未开，可能影响其它接口，需谨慎，改为手动在 controller 内校验 ROLE_ADMIN）。
- 【低】**时间格式**：前端 `el-date-picker` 与后端 `@DateTimeFormat(iso=DATE_TIME)` 需对齐 `yyyy-MM-dd HH:mm:ss` 或 RFC3339，避免解析失败返回 400。单测覆盖 startTime/endTime 边界。
- 【低】**只读约束**：本接口仅 GET，无新增/删除/修改 admin 审计——审计不可变，前端 detail drawer 标"只读不可修改"。