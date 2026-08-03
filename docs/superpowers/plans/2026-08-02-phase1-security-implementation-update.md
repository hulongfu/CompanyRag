# 多租户认证优化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现基于用户 - 租户关联表的多租户认证体系，支持用户关联多个租户并在登录后切换

**Architecture:** 
- 在 public schema 创建 sys_user_tenant_rel 关联表，建立用户与租户的多对多关系
- 修改 JWT Token 结构，携带 tenantIds 列表而非单个 tenantId
- 前端维护 currentTenantId 状态，通过 X-Tenant-Id 请求头传递给后端
- 后端验证 X-Tenant-Id 是否在 tenantIds 中，实现租户访问控制

**Tech Stack:** 
- Spring Boot 3.4.4 + Spring Security 6
- MyBatis-Plus 3.5.9 + PostgreSQL 16 + PGVector
- jjwt 0.12.6
- Vue 3 + Element Plus

---

## 文件结构

### 新增文件
- `company-rag-tenant/src/main/resources/sql/user_tenant_rel_ddl.sql` — 关联表 DDL 脚本
- `company-rag-tenant/src/main/java/com/company/rag/tenant/model/UserTenantRel.java` — 关联表实体
- `company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/UserTenantRelMapper.java` — 关联表 Mapper

### 修改文件
- `company-rag-tenant/src/main/java/com/company/rag/tenant/model/User.java` — 移除 tenantId 字段
- `company-rag-common/src/main/java/com/company/rag/common/security/SecurityUser.java` — 增加 tenantIds 字段
- `company-rag-common/src/main/java/com/company/rag/common/security/JwtTokenProvider.java` — Token 中增加 tenantIds
- `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/JwtAuthenticationFilter.java` — 验证 X-Tenant-Id
- `company-rag-web/src/main/java/com/company/rag/web/model/AuthResponse.java` — 增加 tenantIds 和 currentTenantId
- `company-rag-web/src/main/java/com/company/rag/web/controller/AuthController.java` — 登录返回租户列表
- `company-rag-web/src/main/resources/templates/login.html` — 移除租户编码输入
- `company-rag-web/src/main/resources/templates/index.html` — 租户切换和权限控制

---

## Task 1: 数据库迁移

**Files:**
- Create: `company-rag-tenant/src/main/resources/sql/user_tenant_rel_ddl.sql`
- Test: 手动执行 SQL 验证

- [ ] **Step 1: 创建 DDL 脚本**

```sql
-- 用户 - 租户关联表 DDL
-- 执行数据库：company_rag (public schema)

-- 1. 修改 sys_user 表（移除 tenant_id 字段）
ALTER TABLE sys_user DROP COLUMN IF EXISTS tenant_id;

-- 2. 创建用户 - 租户关联表
CREATE TABLE sys_user_tenant_rel (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    UNIQUE (user_id, tenant_id),
    CONSTRAINT fk_user_tenant_rel_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
    CONSTRAINT fk_user_tenant_rel_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant(id)
);

-- 3. 创建索引（优化查询）
CREATE INDEX idx_user_tenant_rel_user_id ON sys_user_tenant_rel(user_id);
CREATE INDEX idx_user_tenant_rel_tenant_id ON sys_user_tenant_rel(tenant_id);

-- 4. 保留 1 条用户数据（假设保留 id=1 的 admin）
DELETE FROM sys_user WHERE id > 1;

-- 5. 插入 8 条关联关系（admin 关联所有租户）
INSERT INTO sys_user_tenant_rel (user_id, tenant_id)
SELECT 1, id FROM sys_tenant;

-- 6. 验证数据
SELECT '用户数' AS item, COUNT(*) AS count FROM sys_user
UNION ALL
SELECT '关联关系数', COUNT(*) FROM sys_user_tenant_rel
UNION ALL
SELECT '租户数', COUNT(*) FROM sys_tenant;
```

- [ ] **Step 2: 执行 DDL 脚本**

```bash
# 备份现有数据（可选）
pg_dump -h localhost -U postgres -d company_rag -t sys_user > backup_sys_user.sql

# 执行 DDL 脚本
psql -h localhost -U postgres -d company_rag -f company-rag-tenant/src/main/resources/sql/user_tenant_rel_ddl.sql

# 验证结果
psql -h localhost -U postgres -d company_rag -c "SELECT u.username, t.tenant_name FROM sys_user u JOIN sys_user_tenant_rel rel ON u.id = rel.user_id JOIN sys_tenant t ON rel.tenant_id = t.id;"
```

预期输出：
```
 username | tenant_name 
----------+-------------
 admin    | tenant1
 admin    | tenant2
 ...
 admin    | tenant8
(8 rows)
```

- [ ] **Step 3: 提交**

```bash
git add company-rag-tenant/src/main/resources/sql/user_tenant_rel_ddl.sql
git commit -m "feat: 创建用户 - 租户关联表 DDL 脚本"
```

---

## Task 2: 关联表实体和 Mapper

**Files:**
- Create: `company-rag-tenant/src/main/java/com/company/rag/tenant/model/UserTenantRel.java`
- Create: `company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/UserTenantRelMapper.java`

- [ ] **Step 1: 创建实体类**

```java
package com.company.rag.tenant.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户 - 租户关联表实体
 */
@Data
@TableName("sys_user_tenant_rel")
public class UserTenantRel {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private Long userId;
    
    private Long tenantId;
}
```

- [ ] **Step 2: 创建 Mapper 接口**

```java
package com.company.rag.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.tenant.model.UserTenantRel;
import org.apache.ibatis.annotations.Select;
import java.util.List;

public interface UserTenantRelMapper extends BaseMapper<UserTenantRel> {
    
    /**
     * 根据用户 ID 查询关联的租户 ID 列表
     */
    @Select("SELECT tenant_id FROM sys_user_tenant_rel WHERE user_id = #{userId}")
    List<Long> findTenantIdsByUserId(Long userId);
    
    /**
     * 根据租户 ID 查询关联的用户 ID 列表
     */
    @Select("SELECT user_id FROM sys_user_tenant_rel WHERE tenant_id = #{tenantId}")
    List<Long> findUserIdsByTenantId(Long tenantId);
}
```

- [ ] **Step 3: 提交**

```bash
git add company-rag-tenant/src/main/java/com/company/rag/tenant/model/UserTenantRel.java
git add company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/UserTenantRelMapper.java
git commit -m "feat: 创建用户 - 租户关联表实体和 Mapper"
```

---

## Task 3: 修改 User 实体（移除 tenantId）

**Files:**
- Modify: `company-rag-tenant/src/main/java/com/company/rag/tenant/model/User.java`

- [ ] **Step 1: 查看当前 User 实体**

读取文件，确认当前结构。

- [ ] **Step 2: 移除 tenantId 字段**

```java
package com.company.rag.tenant.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户实体
 */
@Data
@TableName("sys_user")
public class User {
    
    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String username;
    
    private String password;
    
    private Integer status;
    
    // ✅ 移除 tenantId 字段（用户 - 租户关系通过关联表维护）
}
```

- [ ] **Step 3: 提交**

```bash
git add company-rag-tenant/src/main/java/com/company/rag/tenant/model/User.java
git commit -m "refactor: 移除 User 实体的 tenantId 字段"
```

---

## Task 4: 修改 SecurityUser（增加 tenantIds）

**Files:**
- Modify: `company-rag-common/src/main/java/com/company/rag/common/security/SecurityUser.java`

- [ ] **Step 1: 查看当前 SecurityUser**

读取文件，确认当前结构。

- [ ] **Step 2: 增加 tenantIds 字段**

```java
package com.company.rag.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 安全用户上下文（实现 Spring Security UserDetails）
 */
@Getter
public class SecurityUser implements UserDetails {
    
    private final Long userId;
    private final Long tenantId;  // ✅ 当前租户 ID
    private final List<Long> tenantIds;  // ✅ 可访问的租户列表（新增）
    private final String username;
    private final String password;
    private final Collection<? extends GrantedAuthority> authorities;
    private final boolean enabled;
    
    public SecurityUser(Long userId, 
                       Long tenantId,
                       List<Long> tenantIds,  // ✅ 新增参数
                       String username, 
                       String password, 
                       String role,
                       boolean enabled) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.tenantIds = tenantIds != null ? tenantIds : Collections.emptyList();  // ✅
        this.username = username;
        this.password = password;
        this.authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        this.enabled = enabled;
    }
    
    // ... 其他方法保持不变
}
```

- [ ] **Step 3: 提交**

```bash
git add company-rag-common/src/main/java/com/company/rag/common/security/SecurityUser.java
git commit -m "feat: SecurityUser 增加 tenantIds 字段"
```

---

## Task 5: 修改 JwtTokenProvider（Token 携带 tenantIds）

**Files:**
- Modify: `company-rag-common/src/main/java/com/company/rag/common/security/JwtTokenProvider.java`

- [ ] **Step 1: 查看当前 JwtTokenProvider**

读取文件，确认当前结构。

- [ ] **Step 2: 修改 generateToken 方法**

```java
/**
 * 生成 JWT Token
 * 
 * @param securityUser 安全用户
 * @param refreshToken 是否为刷新令牌
 * @return JWT 令牌字符串
 */
public String generateToken(SecurityUser securityUser, boolean refreshToken) {
    Date now = new Date();
    Date expiryDate = refreshToken ? 
        new Date(now.getTime() + refreshExpirationMs) : 
        new Date(now.getTime() + expirationMs);
    
    Claims claims = Jwts.claims().setSubject(String.valueOf(securityUser.getUserId()));
    claims.put("userId", securityUser.getUserId());
    claims.put("username", securityUser.getUsername());
    claims.put("tenantId", securityUser.getTenantId());
    claims.put("tenantIds", securityUser.getTenantIds());  // ✅ 新增
    claims.put("role", securityUser.getAuthorities().stream()
        .findFirst()
        .map(a -> a.getAuthority().replace("ROLE_", ""))
        .orElse("user"));
    
    return Jwts.builder()
        .setClaims(claims)
        .setIssuedAt(now)
        .setExpiration(expiryDate)
        .signWith(key, SignatureAlgorithm.HS256)
        .compact();
}
```

- [ ] **Step 3: 修改 parseToken 方法（增加 tenantIds 解析）**

确保解析方法能正确获取 tenantIds：

```java
/**
 * 从 Token 中获取租户 ID 列表
 */
public List<Long> getTenantIdsFromToken(String token) {
    Claims claims = parseToken(token);
    return claims.get("tenantIds", List.class);
}
```

- [ ] **Step 4: 提交**

```bash
git add company-rag-common/src/main/java/com/company/rag/common/security/JwtTokenProvider.java
git commit -m "feat: JWT Token 增加 tenantIds 字段"
```

---

## Task 6: 修改 JwtAuthenticationFilter（验证 X-Tenant-Id）

**Files:**
- Modify: `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/JwtAuthenticationFilter.java`

- [ ] **Step 1: 查看当前 JwtAuthenticationFilter**

读取文件，确认当前结构。

- [ ] **Step 2: 修改验证逻辑**

```java
@Override
protected void doFilterInternal(HttpServletRequest request, 
                               HttpServletResponse response, 
                               FilterChain filterChain) throws ServletException, IOException {
    try {
        // 1. 从请求头获取 Authorization
        String authHeader = request.getHeader("Authorization");
        String token = extractToken(authHeader);
        
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        
        // 2. 验证 Token 是否有效
        if (!jwtTokenProvider.validateToken(token)) {
            throw new AuthenticationException("Token 无效或已过期");
        }
        
        // 3. 解析 Token
        Claims claims = jwtTokenProvider.parseToken(token);
        Long userId = claims.get("userId", Long.class);
        List<Long> userTenantIds = claims.get("tenantIds", List.class);  // ✅ 获取 tenantIds
        
        // 4. 从请求头获取 X-Tenant-Id
        String tenantIdHeader = request.getHeader("X-Tenant-Id");
        if (tenantIdHeader == null) {
            throw new AuthenticationException("缺少租户 ID");
        }
        Long requestTenantId = Long.parseLong(tenantIdHeader);
        
        // 5. 验证：请求的租户是否在用户的 tenantIds 中？
        if (userTenantIds == null || !userTenantIds.contains(requestTenantId)) {
            throw new AuthenticationException("无权访问该租户");  // ✅ 新增验证
        }
        
        // 6. 设置上下文（使用请求的租户 ID，而非 Token 中的 tenantId）
        TenantContext.setTenantId(requestTenantId);  // ✅ 修改
        
        // 7. 设置 SecurityContext
        SecurityUser securityUser = new SecurityUser(
            userId,
            requestTenantId,  // ✅ 使用当前请求的租户 ID
            userTenantIds,    // ✅ 传入 tenantIds
            claims.get("username"),
            null,  // 密码不放入 SecurityUser
            claims.get("role"),
            true
        );
        
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(
                securityUser,
                null,
                securityUser.getAuthorities()
            );
        
        SecurityContextHolder.getContext().setAuthentication(authentication);
        
    } catch (AuthenticationException e) {
        SecurityContextHolder.clearContext();
        sendAuthenticationError(response, e.getMessage());
        return;
    }
    
    filterChain.doFilter(request, response);
}
```

- [ ] **Step 3: 提交**

```bash
git add company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/JwtAuthenticationFilter.java
git commit -m "feat: 验证 X-Tenant-Id 是否在 tenantIds 中"
```

---

## Task 7: 修改 AuthResponse（增加 tenantIds 和 currentTenantId）

**Files:**
- Modify: `company-rag-web/src/main/java/com/company/rag/web/model/AuthResponse.java`

- [ ] **Step 1: 查看当前 AuthResponse**

读取文件，确认当前结构。

- [ ] **Step 2: 增加字段**

```java
package com.company.rag.web.model;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 认证响应
 */
@Data
@Builder
public class AuthResponse {
    
    private String token;
    
    private String refreshToken;
    
    private Long userId;
    
    private String username;
    
    private String role;
    
    private List<Long> tenantIds;  // ✅ 可访问的租户列表（新增）
    
    private Long currentTenantId;  // ✅ 当前租户 ID（默认租户，新增）
}
```

- [ ] **Step 3: 提交**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/model/AuthResponse.java
git commit -m "feat: AuthResponse 增加 tenantIds 和 currentTenantId"
```

---

## Task 8: 修改 AuthController（登录返回租户列表）

**Files:**
- Modify: `company-rag-web/src/main/java/com/company/rag/web/controller/AuthController.java`

- [ ] **Step 1: 查看当前 AuthController**

读取文件，确认当前结构。

- [ ] **Step 2: 修改登录逻辑**

```java
@PostMapping("/login")
public R<AuthResponse> login(@RequestBody AuthRequest request) {
    try {
        // 1. 执行认证
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(
                request.getUsername(),
                request.getPassword()
            )
        );
        
        // 2. 获取用户信息
        SecurityUser securityUser = (SecurityUser) authentication.getPrincipal();
        
        // 3. 查询用户关联的租户列表（从 CustomUserDetailsService 或 TenantService）
        List<Long> tenantIds = tenantService.findTenantIdsByUserId(securityUser.getUserId());
        
        // 4. 验证：至少关联一个租户
        if (tenantIds == null || tenantIds.isEmpty()) {
            return R.fail(403, "当前用户没有关联任何租户，没有权限登录");
        }
        
        // 5. 选择默认租户（第一个）
        Long currentTenantId = tenantIds.get(0);
        
        // 6. 生成 Token
        String token = jwtTokenProvider.generateToken(securityUser, false);
        String refreshToken = jwtTokenProvider.generateToken(securityUser, true);
        
        // 7. 记录审计日志
        auditLogService.logLoginSuccess(
            securityUser.getUserId(),
            securityUser.getUsername(),
            requestHeadersUtils.getClientIp()
        );
        
        // 8. 返回响应
        return R.success(AuthResponse.builder()
            .token(token)
            .refreshToken(refreshToken)
            .userId(securityUser.getUserId())
            .username(securityUser.getUsername())
            .role(securityUser.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .orElse("user"))
            .tenantIds(tenantIds)  // ✅ 新增
            .currentTenantId(currentTenantId)  // ✅ 新增
            .build());
            
    } catch (BadCredentialsException e) {
        // 记录登录失败审计日志
        auditLogService.logLoginFailed(
            request.getUsername(),
            "用户名或密码错误",
            requestHeadersUtils.getClientIp()
        );
        return R.fail(401, "用户名或密码错误");
    } catch (AuthenticationException e) {
        return R.fail(401, e.getMessage());
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/controller/AuthController.java
git commit -m "feat: 登录返回 tenantIds 和 currentTenantId"
```

---

## Task 9: 修改前端登录页面

**Files:**
- Modify: `company-rag-web/src/main/resources/templates/login.html`

- [ ] **Step 1: 移除租户编码输入框**

```html
<el-form :model="form" label-position="top">
    <el-form-item label="用户名">
        <el-input v-model="form.username" placeholder="请输入用户名" />
    </el-form-item>
    <el-form-item label="密码">
        <el-input type="password" v-model="form.password" placeholder="请输入密码" show-password />
    </el-form-item>
    <!-- ✅ 移除租户编码输入框 -->
    <el-form-item>
        <el-button type="primary" @click="handleLogin" size="large">登 录</el-button>
    </el-form-item>
</el-form>
```

- [ ] **Step 2: 修改登录成功处理逻辑**

```javascript
function handleLogin() {
    fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            username: form.value.username,
            password: form.value.password
        })
    })
    .then(res => res.json())
    .then(data => {
        if (data.code === 200) {
            // 存储 Token 到 localStorage
            localStorage.setItem('token', data.data.token);
            localStorage.setItem('refreshToken', data.data.refreshToken);
            localStorage.setItem('userId', data.data.userId);
            localStorage.setItem('tenantIds', JSON.stringify(data.data.tenantIds));  // ✅
            localStorage.setItem('currentTenantId', data.data.currentTenantId);  // ✅
            localStorage.setItem('userInfo', JSON.stringify({
                userId: data.data.userId,
                username: data.data.username,
                role: data.data.role
            }));
            ElementPlus.ElMessage.success('登录成功');
            // 重定向回原来的页面（如果有 redirect 参数），否则跳转到首页
            const urlParams = new URLSearchParams(window.location.search);
            const redirect = urlParams.get('redirect');
            window.location.href = redirect || '/index';
        } else {
            ElementPlus.ElMessage.error(data.message || '登录失败');
        }
    })
    .catch(err => {
        ElementPlus.ElMessage.error('登录失败：' + err.message);
    });
}
```

- [ ] **Step 3: 提交**

```bash
git add company-rag-web/src/main/resources/templates/login.html
git commit -m "feat: 登录页面移除租户编码输入，存储 tenantIds"
```

---

## Task 10: 修改前端首页（租户切换和权限控制）

**Files:**
- Modify: `company-rag-web/src/main/resources/templates/index.html`

- [ ] **Step 1: 修改 API 请求封装（使用 currentTenantId）**

```javascript
// 统一的 API 请求包装函数，处理 401 认证失败
async function apiRequest(url, options = {}) {
    const currentTenantId = localStorage.getItem('currentTenantId');
    const token = localStorage.getItem('token');
    
    const headers = {
        ...options.headers,
        'Authorization': 'Bearer ' + token,
        'X-Tenant-Id': currentTenantId  // ✅ 使用当前租户 ID
    };
    
    try {
        const res = await fetch(url, { ...options, headers });
        // 如果是 401，说明未认证或 Token 过期，跳转到登录页
        if (res.status === 401) {
            ElementPlus.ElMessage.error('登录已过期，请重新登录');
            window.location.href = '/login';
            return null;
        }
        const json = await res.json();
        // 如果响应体中 code 为 401，也跳转到登录页
        if (json && json.code === 401) {
            ElementPlus.ElMessage.error('登录已过期，请重新登录');
            window.location.href = '/login';
            return null;
        }
        return json;
    } catch(e) {
        console.error('API 请求失败', e);
        throw e;
    }
}
```

- [ ] **Step 2: 增加租户切换功能**

在租户管理部分添加切换下拉框：

```html
<!-- 租户管理 Tab -->
<el-tab-pane label="租户管理" name="tenant">
    <div style="margin-bottom: 16px;">
        <span>当前租户：</span>
        <el-select v-model="currentTenantId" @change="switchTenant" style="width: 200px;">
            <el-option 
                v-for="id in tenantIds" 
                :key="id" 
                :label="'租户 ' + id" 
                :value="id" 
            />
        </el-select>
    </div>
    
    <!-- ✅ 仅管理员显示新增按钮 -->
    <el-button v-if="role === 'admin'" type="primary" @click="showCreateTenant = true">
        新增租户
    </el-button>
    
    <!-- 租户列表表格 -->
    <el-table :data="tenants" style="width: 100%">
        <el-table-column prop="tenantCode" label="租户编码" />
        <el-table-column prop="tenantName" label="租户名称" />
        <el-table-column prop="schemaName" label="Schema" />
        <el-table-column label="操作">
            <template #default="{ row }">
                <!-- ✅ 仅管理员显示删除按钮 -->
                <el-button v-if="role === 'admin'" type="danger" size="small" @click="deleteTenant(row)">
                    删除
                </el-button>
            </template>
        </el-table-column>
    </el-table>
</el-tab-pane>
```

- [ ] **Step 3: 增加 switchTenant 方法**

```javascript
// 租户切换
function switchTenant(newTenantId) {
    localStorage.setItem('currentTenantId', newTenantId);
    ElementPlus.ElMessage.success('已切换到租户 ' + newTenantId);
    // 刷新页面或重新加载数据
    location.reload();
}

// 在 setup 中返回
return {
    // ... 其他
    currentTenantId,
    tenantIds,
    role,
    switchTenant,
    // ...
};
```

- [ ] **Step 4: 在 onMounted 中初始化 tenantIds 和 currentTenantId**

```javascript
onMounted(() => {
    // 从 localStorage 加载 tenantIds 和 currentTenantId
    const storedTenantIds = localStorage.getItem('tenantIds');
    const storedCurrentTenantId = localStorage.getItem('currentTenantId');
    
    if (storedTenantIds) {
        tenantIds.value = JSON.parse(storedTenantIds);
    }
    if (storedCurrentTenantId) {
        currentTenantId.value = storedCurrentTenantId;
    }
    
    // 加载数据
    loadDocuments();
    loadSessions();
    loadTenants();
});
```

- [ ] **Step 5: 提交**

```bash
git add company-rag-web/src/main/resources/templates/index.html
git commit -m "feat: 首页增加租户切换和权限控制"
```

---

## Task 11: 编译验证

**Files:** 所有修改的文件

- [ ] **Step 1: 编译项目**

```bash
cd D:/tmp/CompanyRag
mvn clean compile -q
```

预期：编译成功

- [ ] **Step 2: 修复编译错误（如果有）**

根据编译错误逐个修复。

- [ ] **Step 3: 提交**

```bash
git add -A
git commit -m "build: 编译验证通过"
```

---

## Task 12: 手动测试

**Files:** 浏览器

- [ ] **Step 1: 启动应用**

```bash
mvn spring-boot:run -pl company-rag-bootstrap
```

- [ ] **Step 2: 测试登录**

1. 打开浏览器无痕窗口，访问 `http://localhost:8080/`
2. 自动跳转到 `/login`
3. 输入 admin/admin123，点击登录
4. 验证：应该成功登录，返回 tenantIds=[1,2,3,4,5,6,7,8]，currentTenantId=1

- [ ] **Step 3: 测试租户切换**

1. 在首页点击"租户管理"标签
2. 验证：显示租户列表下拉框
3. 选择租户 2
4. 验证：页面刷新，currentTenantId 变为 2
5. 调用 API 时携带 X-Tenant-Id: 2

- [ ] **Step 4: 测试权限控制**

1. 验证：admin 用户显示"新增租户"和"删除"按钮
2. （可选）创建普通用户，验证不显示管理按钮

- [ ] **Step 5: 记录测试结果**

记录所有测试步骤的结果，如有问题记录错误信息。

---

## Task 13: 更新 README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 更新认证说明**

在 README 的认证部分增加多租户认证说明：

```markdown
### 认证流程

1. 访问 `http://localhost:8080/` → 自动跳转到登录页
2. 输入用户名密码（不需要租户编码）
3. 登录成功后，自动选择第一个关联的租户作为当前租户
4. 在"租户管理"页面可以查看可访问的租户列表，并进行租户切换

### 多租户说明

- 一个用户可以关联多个租户
- Token 携带可访问的租户列表（tenantIds）
- 当前租户 ID 通过 X-Tenant-Id 请求头传递
- 租户切换时不需要重新登录，只需要更新 currentTenantId
```

- [ ] **Step 2: 提交**

```bash
git add README.md
git commit -m "docs: 更新多租户认证说明"
```

---

## 自审清单

**1. Spec 覆盖检查：**
- [ ] 用户 - 租户关联表 DDL ✅
- [ ] User 实体移除 tenantId ✅
- [ ] SecurityUser 增加 tenantIds ✅
- [ ] JwtTokenProvider 生成携带 tenantIds 的 Token ✅
- [ ] JwtAuthenticationFilter 验证 X-Tenant-Id ✅
- [ ] AuthResponse 增加 tenantIds 和 currentTenantId ✅
- [ ] AuthController 登录返回租户列表 ✅
- [ ] 前端登录页面移除租户编码输入 ✅
- [ ] 前端首页增加租户切换 ✅
- [ ] 权限控制（管理员 vs 普通用户）✅

**2. 占位符扫描：**
- [ ] 无"TBD"、"TODO"等占位符
- [ ] 所有代码步骤都有完整代码
- [ ] 所有命令都有预期输出

**3. 类型一致性检查：**
- [ ] SecurityUser.tenantIds 类型：List<Long>
- [ ] JwtTokenProvider 中 tenantIds 字段名一致
- [ ] AuthResponse.tenantIds 类型：List<Long>
- [ ] 前端 tenantIds 存储为 JSON 字符串

---

**Plan complete and saved to `docs/superpowers/plans/2026-08-02-phase1-security-implementation-update.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
