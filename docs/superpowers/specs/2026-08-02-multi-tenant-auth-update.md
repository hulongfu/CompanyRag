# 多租户认证优化设计文档 — CompanyRag

> **补充设计**：基于用户 - 租户关联表的多租户认证方案  
> 日期：2026-08-02  
> 状态：设计评审通过（待实施）  
> 关联文档：`docs/superpowers/specs/2026-08-02-production-hardening-design.md`

---

## 1. 背景与问题

### 1.1 当前问题

阶段一实现的 JWT 认证体系存在多租户兼容性问题：

**问题场景**：
```
1. 用户登录 → CustomUserDetailsService.loadUserByUsername("admin")
2. UserMapper.findByUsername() 查询用户
3. MyBatis-Plus TenantLineInnerInterceptor 自动添加 tenant_id = ? 条件
4. 但登录时 TenantContext 未设置（用户未认证），返回 null
5. SQL 变成：SELECT * FROM sys_user WHERE username = 'admin' AND tenant_id = null
6. tenant_id = null 永远为 false → 查询不到用户 → 登录失败
```

**根本矛盾**：
- 登录时需要查询用户表 → 需要设置 TenantContext
- 但 TenantContext 需要在认证后才能设置（从用户信息中获取 tenantId）
- 形成"先有鸡还是先有蛋"的死循环

### 1.2 原方案缺陷

原设计假设"每个租户下有独立的用户表"，登录时需要指定租户编码。但这存在以下问题：

1. **用户体验差**：用户需要知道并输入租户编码
2. **不支持多租户关联**：一个用户无法同时属于多个租户
3. **架构生硬**：不符合 SaaS 产品的主流实践

---

## 2. 优化方案：用户 - 租户关联表

### 2.1 核心设计思想

**多对多关系**：一个租户下有多个用户，一个用户可以关联多个租户。

```
sys_user (用户表，public schema)
    ├── id (PK)
    ├── username
    ├── password
    └── status

sys_tenant (租户表，public schema)
    ├── id (PK)
    ├── tenant_code
    ├── tenant_name
    └── schema_name

sys_user_tenant_rel (关联表，public schema) ⭐ 新增
    ├── id (PK)
    ├── user_id (FK → sys_user.id)
    ├── tenant_id (FK → sys_tenant.id)
    └── UNIQUE (user_id, tenant_id)  // 唯一约束
```

### 2.2  Schema 设计

所有元数据表（用户、租户、关联表）都放在 `public` schema：

| 表名 | Schema | 说明 |
|------|--------|------|
| `sys_user` | public | 用户表（移除 tenant_id 字段） |
| `sys_tenant` | public | 租户表（保持不变，8 条数据） |
| `sys_user_tenant_rel` | public | 用户 - 租户关联表（新增） |
| 业务表（document/chunk/session 等） | 各租户 schema | 租户业务数据，受租户隔离 |

**优点**：
- 登录时不需要 TenantContext，直接查询 public schema
- 用户数据与租户业务数据完全分离
- 符合"元数据 vs 业务数据"的分层设计

---

## 3. 认证流程

### 3.1 登录流程

```
[用户]                      [后端]                        [数据库]
  │                          │                              │
  │  POST /api/auth/login    │                              │
  │  {username, password}    │                              │
  │ ────────────────────────►│                              │
  │                          │  1. 查询 sys_user (public)   │
  │                          │─────────────────────────────►│
  │                          │◄─────────────────────────────│
  │                          │  2. BCrypt 验证密码          │
  │                          │                              │
  │                          │  3. 查询 sys_user_tenant_rel │
  │                          │─────────────────────────────►│
  │                          │◄─────────────────────────────│ 返回 tenantIds
  │                          │                              │
  │                          │  4. 验证：至少关联一个租户？ │
  │                          │     否 → 拒绝："没有关联任何租户"
  │                          │     是 → 继续               │
  │                          │                              │
  │                          │  5. 选择默认租户 (tenantIds[0])
  │                          │                              │
  │                          │  6. 生成 JWT Token
  │                          │     Payload: {
  │                          │       userId,
  │                          │       username,
  │                          │       tenantIds: [1,2,3,...]
  │                          │     }                        │
  │                          │                              │
  │◄─────────────────────────│                              │
  │  {                       │                              │
  │    token,                │                              │
  │    refreshToken,         │                              │
  │    userId,               │                              │
  │    tenantIds,            │                              │
  │    currentTenantId: 1    │                              │
  │  }                       │                              │
```

### 3.2 Token 结构

**JWT Payload**：
```json
{
  "userId": 1,
  "username": "admin",
  "tenantIds": [1, 2, 3, 4, 5, 6, 7, 8],  // ✅ 可访问的租户列表
  "iat": 1722600000,
  "exp": 1722607200
}
```

**关键设计决策**：
- ✅ Token 携带 `tenantIds`（权限凭证，长期不变）
- ❌ Token 不携带 `currentTenantId`（会话状态，频繁变化）

**原因**：
| 字段 | 性质 | 变化频率 | 是否进 Token |
|------|------|----------|-------------|
| `tenantIds` | 权限凭证 | 很少变化 | ✅ 应该 |
| `currentTenantId` | 会话状态 | 每次切换都变 | ❌ 不应该 |

如果 `currentTenantId` 放进 Token：
- 每次切换租户都需要重新生成 Token
- 失去 JWT"无状态"优势
- 增加服务器负担

### 3.3 登录返回

```json
{
  "code": 200,
  "msg": "登录成功",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "userId": 1,
    "tenantIds": [1, 2, 3, 4, 5, 6, 7, 8],
    "currentTenantId": 1  // ✅ 前端状态（默认租户）
  }
}
```

**默认租户选择规则**：
- 选择 `tenantIds` 中的第一个（最简单）
- 未来可扩展：`sys_user_tenant_rel` 增加 `is_default` 字段

---

## 4. 登录后使用流程

### 4.1 前端状态管理

```javascript
// localStorage 存储
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userId": 1,
  "tenantIds": [1, 2, 3, 4, 5, 6, 7, 8],
  "currentTenantId": 1  // ✅ 当前租户 ID（前端状态）
}

// API 调用封装
async function apiRequest(url, options = {}) {
  const currentTenantId = localStorage.getItem('currentTenantId');
  const token = localStorage.getItem('token');
  
  const headers = {
    ...options.headers,
    'Authorization': 'Bearer ' + token,
    'X-Tenant-Id': currentTenantId  // ✅ 使用当前租户 ID
  };
  
  const res = await fetch(url, { ...options, headers });
  
  // 处理 401
  if (res.status === 401) {
    window.location.href = '/login';
    return null;
  }
  
  return await res.json();
}
```

### 4.2 后端验证流程

```java
// JwtAuthenticationFilter.java
@Override
protected void doFilterInternal(HttpServletRequest request, ...) {
    // 1. 从请求头获取 Authorization
    String authHeader = request.getHeader("Authorization");
    String token = extractToken(authHeader);
    
    // 2. 解析 Token
    Claims claims = jwtTokenProvider.parseToken(token);
    Long userId = claims.get("userId", Long.class);
    List<Long> userTenantIds = claims.get("tenantIds", List.class);
    
    // 3. 从请求头获取 X-Tenant-Id
    String tenantIdHeader = request.getHeader("X-Tenant-Id");
    if (tenantIdHeader == null) {
        throw new AuthenticationException("缺少租户 ID");
    }
    Long requestTenantId = Long.parseLong(tenantIdHeader);
    
    // 4. 验证：请求的租户是否在用户的 tenantIds 中？
    if (!userTenantIds.contains(requestTenantId)) {
        throw new AuthenticationException("无权访问该租户");
    }
    
    // 5. 设置上下文
    TenantContext.setTenantId(requestTenantId);  // ✅ 使用当前请求的租户 ID
    
    // 6. 设置 SecurityContext
    SecurityUser securityUser = new SecurityUser(
        userId,
        requestTenantId,  // ✅ 当前租户 ID
        claims.get("username"),
        ...
    );
    SecurityContextHolder.getContext().setAuthentication(...);
    
    chain.doFilter(request, response);
}
```

### 4.3 租户切换流程

```
[用户]                      [前端]                        [后端]
  │                          │                              │
  │  点击租户切换下拉框      │                              │
  │  选择"租户 B (id=2)"     │                              │
  │                          │                              │
  │                          │  1. 更新 localStorage
  │                          │     currentTenantId = 2     │
  │                          │                              │
  │                          │  2. 刷新页面或重新加载数据  │
  │                          │                              │
  │                          │  3. API 调用携带 X-Tenant-Id: 2
  │                          │─────────────────────────────►│
  │                          │                              │
  │                          │                              │  验证 Token
  │                          │                              │  tenantIds 包含 2 → OK
  │                          │                              │  设置 TenantContext(2)
  │                          │                              │  在租户 B schema 下查询
  │                          │◄─────────────────────────────│
  │                          │  返回租户 B 的数据           │
  │                          │                              │
  │◄─────────────────────────│                              │
  │  页面显示租户 B 的信息   │                              │
```

**关键点**：
- ✅ Token 不需要变化（tenantIds 没有变化）
- ✅ 只需要更新 localStorage 中的 `currentTenantId`
- ✅ 后续 API 调用自动携带新的 `X-Tenant-Id`

---

## 5. 权限控制

### 5.1 管理员 vs 普通用户

| 角色 | 租户管理权限 | 说明 |
|------|-------------|------|
| **管理员** | ✅ 完整权限 | 可以新增、编辑、删除租户 |
| **普通用户** | ❌ 只读 | 只能查看自己关联的租户列表，进行租户切换 |

### 5.2 后端 Controller 权限

```java
@RestController
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TenantController {
    
    // ✅ 所有登录用户都可以查看自己的租户列表
    @GetMapping("/list")
    public R<List<TenantVO>> list() {
        SecurityUser user = SecurityUtils.getCurrentUser();
        // 返回用户关联的租户列表（从 Token 中的 tenantIds 查询）
        List<Tenant> tenants = tenantService.findByIds(user.getTenantIds());
        return R.success(tenants);
    }
    
    // ❌ 仅管理员可以创建租户
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(actionType = "CREATE_TENANT", moduleName = "租户管理")
    public R<Tenant> create(@RequestBody TenantCreateRequest request) {
        // ...
    }
    
    // ❌ 仅管理员可以删除租户
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @AuditLog(actionType = "DELETE_TENANT", moduleName = "租户管理")
    public R<Void> delete(@PathVariable Long id) {
        // ...
    }
}
```

### 5.3 前端 UI 权限

```vue
<!-- index.html 中的租户管理部分 -->
<div>
    <h3>租户列表</h3>
    
    <!-- ✅ 所有用户都显示租户列表（可切换） -->
    <el-select v-model="currentTenantId" @change="switchTenant">
        <el-option 
            v-for="tenant in tenants" 
            :key="tenant.id" 
            :label="tenant.tenantName" 
            :value="tenant.id" 
        />
    </el-select>
    
    <!-- ❌ 仅管理员显示"新增租户"按钮 -->
    <el-button v-if="role === 'admin'" @click="showCreateTenant = true">
        新增租户
    </el-button>
    
    <!-- ❌ 仅管理员显示"删除"按钮 -->
    <el-button v-if="role === 'admin'" @click="deleteTenant(tenant)">
        删除
    </el-button>
</div>
```

---

## 6. 数据迁移方案

### 6.1 DDL 脚本

```sql
-- 1. 修改 sys_user 表（移除 tenant_id 字段）
ALTER TABLE sys_user DROP COLUMN IF EXISTS tenant_id;

-- 2. 创建用户 - 租户关联表
CREATE TABLE sys_user_tenant_rel (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    UNIQUE (user_id, tenant_id)
);

-- 3. 创建索引（优化查询）
CREATE INDEX idx_user_tenant_rel_user_id ON sys_user_tenant_rel(user_id);
CREATE INDEX idx_user_tenant_rel_tenant_id ON sys_user_tenant_rel(tenant_id);

-- 4. 保留 1 条用户数据（假设保留 id=1 的 admin）
DELETE FROM sys_user WHERE id > 1;

-- 5. 插入 8 条关联关系（admin 关联所有租户）
INSERT INTO sys_user_tenant_rel (user_id, tenant_id)
SELECT 1, id FROM sys_tenant;
```

### 6.2 执行步骤

1. **备份现有数据**（可选）：
   ```bash
   pg_dump -h localhost -U postgres -d company_rag -t sys_user > backup_sys_user.sql
   ```

2. **执行 DDL 脚本**：
   ```bash
   psql -h localhost -U postgres -d company_rag -f docs/superpowers/migrations/2026-08-02-user-tenant-rel.sql
   ```

3. **验证数据**：
   ```sql
   -- 验证用户数
   SELECT COUNT(*) FROM sys_user;  -- 应该返回 1
   
   -- 验证关联关系
   SELECT u.username, t.tenant_name 
   FROM sys_user u
   JOIN sys_user_tenant_rel rel ON u.id = rel.user_id
   JOIN sys_tenant t ON rel.tenant_id = t.id;
   -- 应该返回 8 条记录：admin 关联 8 个租户
   ```

---

## 7. 模块变更清单

### 7.1 后端修改

| 模块 | 文件 | 变更内容 |
|------|------|----------|
| `company-rag-tenant` | `sql/user_tenant_rel_ddl.sql` | **新增**：建表脚本 |
| `company-rag-tenant` | `model/UserTenantRel.java` | **新增**：关联表实体 |
| `company-rag-tenant` | `mapper/UserTenantRelMapper.java` | **新增**：Mapper 接口 |
| `company-rag-tenant` | `model/User.java` | 移除 `tenantId` 字段 |
| `company-rag-tenant` | `service/TenantService.java` | 新增 `findByUserIds()` 方法 |
| `company-rag-tenant` | `service/impl/TenantServiceImpl.java` | 实现 `findByUserIds()` |
| `company-rag-common` | `security/SecurityUser.java` | 新增 `tenantIds` 字段 |
| `company-rag-common` | `security/JwtTokenProvider.java` | Token 中增加 `tenantIds` |
| `company-rag-bootstrap` | `config/JwtAuthenticationFilter.java` | 验证 X-Tenant-Id 是否在 tenantIds 中 |
| `company-rag-web` | `controller/AuthController.java` | 登录返回 tenantIds + currentTenantId |
| `company-rag-web` | `model/AuthResponse.java` | 新增 `tenantIds` 和 `currentTenantId` 字段 |
| `company-rag-web` | `controller/TenantController.java` | 列表接口返回用户关联的租户 |

### 7.2 前端修改

| 文件 | 变更内容 |
|------|----------|
| `login.html` | 移除租户编码输入，登录后存储 tenantIds + currentTenantId |
| `index.html` | 租户列表只显示可访问的租户，根据角色显示/隐藏管理按钮 |

---

## 8. 测试场景

### 8.1 单元测试

| 测试类 | 测试方法 | 说明 |
|--------|---------|------|
| `JwtTokenProviderTest` | `testGenerateTokenWithTenantIds()` | 生成携带 tenantIds 的 Token |
| `JwtTokenProviderTest` | `testParseTokenWithTenantIds()` | 解析 Token 获取 tenantIds |
| `JwtAuthenticationFilterTest` | `testValidTenantInTenantIds()` | X-Tenant-Id 在 tenantIds 中 → 通过 |
| `JwtAuthenticationFilterTest` | `testInvalidTenantNotInTenantIds()` | X-Tenant-Id 不在 tenantIds 中 → 拒绝 |

### 8.2 集成测试

| 测试场景 | 步骤 | 预期结果 |
|---------|------|---------|
| **正常登录** | admin/admin123 登录 | 返回 tenantIds=[1,2,3,4,5,6,7,8]，currentTenantId=1 |
| **租户切换** | 切换租户到 id=2，调用 API | API 在租户 2 的 schema 下执行 |
| **无权访问** | 修改 Token 伪造 tenantId=99 | 后端拒绝："无权访问该租户" |
| **无关联租户** | 创建用户但不关联租户，尝试登录 | 拒绝："没有关联任何租户" |

---

## 9. 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 存量数据迁移 | 可能丢失用户数据 | 提前备份，编写回滚脚本 |
| Token 结构变化 | 旧 Token 无法解析 | 清除 Redis 中的旧 Token，强制重新登录 |
| 前端兼容性 | 旧前端代码不兼容 | 同步更新前端，灰度发布 |
| 性能影响 | 关联表查询增加延迟 | 添加索引，查询优化 |

---

## 10. 实施计划

详见：`docs/superpowers/plans/2026-08-02-phase1-security-implementation-update.md`

**关键任务**：
1. 创建 DDL 脚本和数据迁移
2. 修改后端代码（实体、Mapper、Service、Filter、TokenProvider）
3. 修改前端代码（登录、租户切换、权限控制）
4. 编写单元测试和集成测试
5. 手动验证完整流程

---

## 11. 附录：与原设计的对比

| 维度 | 原设计 | 优化设计 |
|------|--------|---------|
| 用户表位置 | 各租户 schema | public schema |
| 用户 - 租户关系 | 一对一（user.tenant_id） | 多对多（关联表） |
| 登录输入 | 用户名 + 密码 + 租户编码 | 用户名 + 密码 |
| Token 结构 | 携带单个 tenantId | 携带 tenantIds 列表 |
| 租户切换 | 需要重新登录 | 不需要，更新 X-Tenant-Id 即可 |
| 用户体验 | 需要知道租户编码 | 自动显示可访问租户列表 |
| 架构复杂度 | 简单 | 中等（增加关联表） |

**结论**：优化设计在复杂度可控的前提下，显著提升了用户体验和架构灵活性。
