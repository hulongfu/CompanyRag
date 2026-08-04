# 用户管理功能实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现用户管理功能，包括用户的增删改查，支持多租户关联和角色分配，仅管理员可访问

**Architecture:** 前后端分离架构，前端使用 Vue 3 + Element Plus 在 index.html 中添加用户管理标签页，后端使用 Spring Boot + Spring Security 提供 REST API，通过@PreAuthorize 实现权限控制

**Tech Stack:** Java 17, Spring Boot 3.4, Spring AI 1.0, MyBatis-Plus 3.5.9, Vue 3, Element Plus 2.4.4, PostgreSQL 16 + PGVector

---

### Task 1: 创建 UserDTO 数据传输对象

**Files:**
- Create: `company-rag-web/src/main/java/com/company/rag/web/model/UserDTO.java`
- Test: N/A (DTO 类，无需单元测试)

- [ ] **Step 1: 创建 UserDTO 类**

```java
package com.company.rag.web.model;

import lombok.Data;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 用户管理 DTO
 */
@Data
public class UserDTO {
    
    /**
     * 创建用户请求
     */
    @Data
    public static class CreateRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 50, message = "用户名长度必须在 3-50 个字符之间")
        private String username;
        
        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 100, message = "密码长度必须在 6-100 个字符之间")
        private String password;
        
        @NotBlank(message = "显示名不能为空")
        @Size(max = 100, message = "显示名长度不能超过 100 个字符")
        private String displayName;
        
        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱长度不能超过 100 个字符")
        private String email;
        
        @NotBlank(message = "角色不能为空")
        private String role;  // admin, user, viewer
        
        @NotBlank(message = "至少选择一个租户")
        private List<Long> tenantIds;
    }
    
    /**
     * 更新用户请求
     */
    @Data
    public static class UpdateRequest {
        @Size(max = 50, message = "用户名长度不能超过 50 个字符")
        private String username;
        
        @Size(max = 100, message = "密码长度不能超过 100 个字符")
        private String password;  // 留空表示不修改
        
        @Size(max = 100, message = "显示名长度不能超过 100 个字符")
        private String displayName;
        
        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱长度不能超过 100 个字符")
        private String email;
        
        @NotBlank(message = "角色不能为空")
        private String role;  // admin, user, viewer
        
        @NotBlank(message = "至少选择一个租户")
        private List<Long> tenantIds;
    }
    
    /**
     * 用户响应
     */
    @Data
    public static class UserResponse {
        private Long id;
        private String username;
        private String displayName;
        private String email;
        private String role;
        private Integer status;
        private List<Long> tenantIds;
        private List<String> tenantNames;
        private String createTime;
    }
    
    /**
     * 用户详情响应
     */
    @Data
    public static class UserDetailResponse {
        private Long id;
        private String username;
        private String displayName;
        private String email;
        private String role;
        private Integer status;
        private List<Long> tenantIds;
        private String createTime;
        private String updateTime;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn clean compile -pl company-rag-web -am`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/model/UserDTO.java
git commit -m "feat: 创建用户管理 DTO 类
- CreateRequest: 创建用户请求，包含字段验证
- UpdateRequest: 更新用户请求，密码可选
- UserResponse: 用户列表响应，包含租户信息
- UserDetailResponse: 用户详情响应"
```

---

### Task 2: 创建 UserService 接口

**Files:**
- Create: `company-rag-tenant/src/main/java/com/company/rag/tenant/service/UserService.java`
- Test: N/A (接口定义)

- [ ] **Step 1: 创建 UserService 接口**

```java
package com.company.rag.tenant.service;

import com.company.rag.web.model.UserDTO;
import java.util.List;

/**
 * 用户服务接口
 */
public interface UserService {
    
    /**
     * 创建用户
     * @param request 创建用户请求
     * @return 创建的用户信息
     */
    UserDTO.UserResponse createUser(UserDTO.CreateRequest request);
    
    /**
     * 查询用户列表 (支持筛选)
     * @param role 角色筛选 (可选)
     * @param tenantId 租户 ID 筛选 (可选)
     * @param status 状态筛选 (可选)
     * @param username 用户名模糊搜索 (可选)
     * @return 用户列表
     */
    List<UserDTO.UserResponse> queryUserList(String role, Long tenantId, Integer status, String username);
    
    /**
     * 根据 ID 查询用户详情
     * @param id 用户 ID
     * @return 用户详情
     */
    UserDTO.UserDetailResponse getUserById(Long id);
    
    /**
     * 更新用户信息
     * @param id 用户 ID
     * @param request 更新用户请求
     * @return 更新后的用户信息
     */
    UserDTO.UserResponse updateUser(Long id, UserDTO.UpdateRequest request);
    
    /**
     * 删除用户
     * @param id 用户 ID
     * @return 是否删除成功
     */
    boolean deleteUser(Long id);
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn clean compile -pl company-rag-tenant -am`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add company-rag-tenant/src/main/java/com/company/rag/tenant/service/UserService.java
git commit -m "feat: 创建用户服务接口
- createUser: 创建用户
- queryUserList: 查询用户列表 (支持筛选)
- getUserById: 查询用户详情
- updateUser: 更新用户信息
- deleteUser: 删除用户"
```

---

### Task 3: 创建 UserServiceImpl 实现类

**Files:**
- Create: `company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/UserServiceImpl.java`
- Modify: `company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/UserMapper.java` (添加方法)
- Modify: `company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/UserTenantRelMapper.java` (添加方法)
- Test: `company-rag-tenant/src/test/java/com/company/rag/tenant/service/UserServiceTest.java`

- [ ] **Step 1: 扩展 UserMapper 接口**

```java
// 在 UserMapper.java 中添加以下方法

/**
 * 根据用户名查询用户 (用于验证唯一性)
 */
@Select("SELECT COUNT(*) FROM sys_user WHERE username = #{username}")
int countByUsername(String username);

/**
 * 根据租户 ID 查询用户列表 (关联租户表)
 */
@Select("<script>" +
        "SELECT u.* FROM sys_user u " +
        "INNER JOIN sys_user_tenant_rel rel ON u.id = rel.user_id " +
        "WHERE rel.tenant_id = #{tenantId}" +
        "<if test='status != null'> AND u.status = #{status}</if>" +
        "</script>")
List<User> findByTenantId(@Param("tenantId") Long tenantId, @Param("status") Integer status);
```

- [ ] **Step 2: 扩展 UserTenantRelMapper 接口**

```java
// 在 UserTenantRelMapper.java 中添加以下方法

/**
 * 根据用户 ID 删除关联记录
 */
@Delete("DELETE FROM sys_user_tenant_rel WHERE user_id = #{userId}")
void deleteByUserId(Long userId);

/**
 * 批量插入用户 - 租户关联
 */
@Insert("<script>" +
        "INSERT INTO sys_user_tenant_rel (user_id, tenant_id) VALUES " +
        "<foreach collection='tenantIds' item='tenantId' separator=','>" +
        "(#{userId}, #{tenantId})" +
        "</foreach>" +
        "</script>")
void batchInsert(@Param("userId") Long userId, @Param("tenantIds") List<Long> tenantIds);

/**
 * 根据用户 ID 查询租户名称列表
 */
@Select("<script>" +
        "SELECT t.tenant_name FROM sys_user_tenant_rel rel " +
        "INNER JOIN sys_tenant t ON rel.tenant_id = t.id " +
        "WHERE rel.user_id = #{userId}" +
        "</script>")
List<String> findTenantNamesByUserId(Long userId);
```

- [ ] **Step 3: 创建 UserServiceImpl 实现类**

```java
package com.company.rag.tenant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.common.security.PasswordGenerator;
import com.company.rag.tenant.mapper.UserMapper;
import com.company.rag.tenant.mapper.UserTenantRelMapper;
import com.company.rag.tenant.model.Tenant;
import com.company.rag.tenant.model.User;
import com.company.rag.tenant.model.UserTenantRel;
import com.company.rag.tenant.service.TenantService;
import com.company.rag.tenant.service.UserService;
import com.company.rag.web.model.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户服务实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    
    private final UserMapper userMapper;
    private final UserTenantRelMapper userTenantRelMapper;
    private final TenantService tenantService;
    
    private static final DateTimeFormatter DATE_FORMATTER = 
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserDTO.UserResponse createUser(UserDTO.CreateRequest request) {
        log.info("创建用户：username={}, role={}, tenantIds={}", 
                request.getUsername(), request.getRole(), request.getTenantIds());
        
        // 1. 验证用户名唯一性
        if (userMapper.countByUsername(request.getUsername()) > 0) {
            throw new IllegalArgumentException("用户名已存在：" + request.getUsername());
        }
        
        // 2. 验证角色合法性
        if (!isValidRole(request.getRole())) {
            throw new IllegalArgumentException("无效的角色：" + request.getRole());
        }
        
        // 3. 验证租户存在性
        for (Long tenantId : request.getTenantIds()) {
            Tenant tenant = tenantService.getById(tenantId);
            if (tenant == null) {
                throw new IllegalArgumentException("租户不存在：" + tenantId);
            }
        }
        
        // 4. 创建用户
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(PasswordGenerator.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setRole(request.getRole());
        user.setStatus(1); // 默认启用
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        
        userMapper.insert(user);
        log.info("用户创建成功：id={}", user.getId());
        
        // 5. 创建用户 - 租户关联
        userTenantRelMapper.batchInsert(user.getId(), request.getTenantIds());
        log.info("用户 - 租户关联创建成功：userId={}, tenantIds={}", 
                user.getId(), request.getTenantIds());
        
        // 6. 构建响应
        return buildUserResponse(user);
    }
    
    @Override
    public List<UserDTO.UserResponse> queryUserList(String role, Long tenantId, 
                                                     Integer status, String username) {
        log.info("查询用户列表：role={}, tenantId={}, status={}, username={}", 
                role, tenantId, status, username);
        
        List<User> users;
        
        // 如果指定了租户 ID，按租户查询
        if (tenantId != null) {
            users = userMapper.findByTenantId(tenantId, status);
        } else {
            // 否则查询所有用户
            LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
            if (StringUtils.hasText(role)) {
                wrapper.eq(User::getRole, role);
            }
            if (status != null) {
                wrapper.eq(User::getStatus, status);
            }
            if (StringUtils.hasText(username)) {
                wrapper.like(User::getUsername, username);
            }
            users = userMapper.selectList(wrapper);
        }
        
        // 构建响应
        return users.stream()
                .map(this::buildUserResponse)
                .collect(Collectors.toList());
    }
    
    @Override
    public UserDTO.UserDetailResponse getUserById(Long id) {
        log.info("查询用户详情：id={}", id);
        
        User user = userMapper.selectById(id);
        if (user == null) {
            return null;
        }
        
        List<Long> tenantIds = userTenantRelMapper.findTenantIdsByUserId(id);
        
        UserDTO.UserDetailResponse response = new UserDTO.UserDetailResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setTenantIds(tenantIds);
        response.setCreateTime(user.getCreateTime() != null ? 
                user.getCreateTime().format(DATE_FORMATTER) : null);
        response.setUpdateTime(user.getUpdateTime() != null ? 
                user.getUpdateTime().format(DATE_FORMATTER) : null);
        
        return response;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserDTO.UserResponse updateUser(Long id, UserDTO.UpdateRequest request) {
        log.info("更新用户：id={}, username={}, role={}", id, request.getUsername(), request.getRole());
        
        // 1. 查询用户
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new IllegalArgumentException("用户不存在：" + id);
        }
        
        // 2. 验证角色合法性
        if (!isValidRole(request.getRole())) {
            throw new IllegalArgumentException("无效的角色：" + request.getRole());
        }
        
        // 3. 如果修改了用户名，验证唯一性
        if (StringUtils.hasText(request.getUsername()) && 
            !request.getUsername().equals(user.getUsername())) {
            if (userMapper.countByUsername(request.getUsername()) > 0) {
                throw new IllegalArgumentException("用户名已存在：" + request.getUsername());
            }
            user.setUsername(request.getUsername());
        }
        
        // 4. 更新密码 (如果提供)
        if (StringUtils.hasText(request.getPassword())) {
            user.setPassword(PasswordGenerator.encode(request.getPassword()));
        }
        
        // 5. 更新其他字段
        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setRole(request.getRole());
        user.setUpdateTime(LocalDateTime.now());
        
        userMapper.updateById(user);
        log.info("用户信息更新成功：id={}", id);
        
        // 6. 更新用户 - 租户关联
        userTenantRelMapper.deleteByUserId(id);
        userTenantRelMapper.batchInsert(id, request.getTenantIds());
        log.info("用户 - 租户关联更新成功：userId={}, tenantIds={}", id, request.getTenantIds());
        
        // 7. 构建响应
        return buildUserResponse(user);
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteUser(Long id) {
        log.info("删除用户：id={}", id);
        
        // 1. 查询用户
        User user = userMapper.selectById(id);
        if (user == null) {
            return false;
        }
        
        // 2. 删除用户 - 租户关联
        userTenantRelMapper.deleteByUserId(id);
        log.info("用户 - 租户关联删除成功：userId={}", id);
        
        // 3. 删除用户
        userMapper.deleteById(id);
        log.info("用户删除成功：id={}", id);
        
        return true;
    }
    
    /**
     * 验证角色是否合法
     */
    private boolean isValidRole(String role) {
        return "admin".equals(role) || "user".equals(role) || "viewer".equals(role);
    }
    
    /**
     * 构建用户响应对象
     */
    private UserDTO.UserResponse buildUserResponse(User user) {
        UserDTO.UserResponse response = new UserDTO.UserResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());
        response.setStatus(user.getStatus());
        response.setCreateTime(user.getCreateTime() != null ? 
                user.getCreateTime().format(DATE_FORMATTER) : null);
        
        // 查询租户信息
        List<Long> tenantIds = userTenantRelMapper.findTenantIdsByUserId(user.getId());
        List<String> tenantNames = userTenantRelMapper.findTenantNamesByUserId(user.getId());
        
        response.setTenantIds(tenantIds);
        response.setTenantNames(tenantNames);
        
        return response;
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn clean compile -pl company-rag-tenant -am`
Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/UserServiceImpl.java
git add company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/UserMapper.java
git add company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/UserTenantRelMapper.java
git commit -m "feat: 实现用户服务
- UserServiceImpl: 完整的 CRUD 实现
- UserMapper: 添加 countByUsername, findByTenantId 方法
- UserTenantRelMapper: 添加 deleteByUserId, batchInsert, findTenantNamesByUserId 方法
- 支持事务管理
- 使用 PasswordGenerator 加密密码"
```

---

### Task 4: 创建 UserController

**Files:**
- Create: `company-rag-web/src/main/java/com/company/rag/web/controller/UserController.java`
- Test: `company-rag-web/src/test/java/com/company/rag/web/controller/UserControllerTest.java`

- [ ] **Step 1: 创建 UserController**

```java
package com.company.rag.web.controller;

import com.company.rag.common.annotation.AuditLog;
import com.company.rag.common.model.R;
import com.company.rag.tenant.service.UserService;
import com.company.rag.web.model.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理接口
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class UserController {
    
    private final UserService userService;
    
    /**
     * 1. 创建用户
     */
    @PostMapping
    @AuditLog(actionType = "CREATE_USER", targetType = "user", 
              detail = "'创建用户：' + #request.username")
    public R<UserDTO.UserResponse> create(@RequestBody @Validated UserDTO.CreateRequest request) {
        try {
            log.info("接收到创建用户请求：username={}", request.getUsername());
            UserDTO.UserResponse response = userService.createUser(request);
            return R.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("创建用户失败：{}", e.getMessage());
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("创建用户异常", e);
            return R.fail(500, "创建用户失败：" + e.getMessage());
        }
    }
    
    /**
     * 2. 查询用户列表 (支持筛选)
     */
    @GetMapping("/list")
    public R<List<UserDTO.UserResponse>> list(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String username) {
        try {
            log.debug("查询用户列表：role={}, tenantId={}, status={}, username={}", 
                    role, tenantId, status, username);
            List<UserDTO.UserResponse> users = userService.queryUserList(role, tenantId, status, username);
            return R.ok(users);
        } catch (Exception e) {
            log.error("查询用户列表异常", e);
            return R.fail(500, "查询用户列表失败：" + e.getMessage());
        }
    }
    
    /**
     * 3. 查询用户详情
     */
    @GetMapping("/{id}")
    public R<UserDTO.UserDetailResponse> getById(@PathVariable Long id) {
        try {
            log.debug("查询用户详情：id={}", id);
            UserDTO.UserDetailResponse user = userService.getUserById(id);
            if (user == null) {
                return R.fail(404, "用户不存在");
            }
            return R.ok(user);
        } catch (Exception e) {
            log.error("查询用户详情异常", e);
            return R.fail(500, "查询用户详情失败：" + e.getMessage());
        }
    }
    
    /**
     * 4. 更新用户
     */
    @PutMapping("/{id}")
    @AuditLog(actionType = "UPDATE_USER", targetType = "user", 
              targetId = "#id", detail = "'更新用户：ID=' + #id")
    public R<UserDTO.UserResponse> update(@PathVariable Long id, 
                                          @RequestBody @Validated UserDTO.UpdateRequest request) {
        try {
            log.info("接收到更新用户请求：id={}, username={}", id, request.getUsername());
            UserDTO.UserResponse response = userService.updateUser(id, request);
            return R.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("更新用户失败：{}", e.getMessage());
            return R.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("更新用户异常", e);
            return R.fail(500, "更新用户失败：" + e.getMessage());
        }
    }
    
    /**
     * 5. 删除用户
     */
    @DeleteMapping("/{id}")
    @AuditLog(actionType = "DELETE_USER", targetType = "user", 
              targetId = "#id", detail = "'删除用户：ID=' + #id")
    public R<Boolean> delete(@PathVariable Long id) {
        try {
            log.info("接收到删除用户请求：id={}", id);
            boolean success = userService.deleteUser(id);
            if (!success) {
                return R.fail(404, "用户不存在");
            }
            return R.ok(true);
        } catch (Exception e) {
            log.error("删除用户异常", e);
            return R.fail(500, "删除用户失败：" + e.getMessage());
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn clean compile -pl company-rag-web -am`
Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/controller/UserController.java
git commit -m "feat: 创建用户管理 REST 控制器
- POST /api/user: 创建用户
- GET /api/user/list: 查询用户列表 (支持筛选)
- GET /api/user/{id}: 查询用户详情
- PUT /api/user/{id}: 更新用户
- DELETE /api/user/{id}: 删除用户
- 使用@PreAuthorize 限制只有管理员可访问
- 添加审计日志记录"
```

---

### Task 5: 编写 UserService 单元测试

**Files:**
- Create: `company-rag-tenant/src/test/java/com/company/rag/tenant/service/UserServiceTest.java`

- [ ] **Step 1: 创建 UserService 单元测试**

```java
package com.company.rag.tenant.service;

import com.company.rag.tenant.mapper.UserMapper;
import com.company.rag.tenant.mapper.UserTenantRelMapper;
import com.company.rag.tenant.model.Tenant;
import com.company.rag.tenant.model.User;
import com.company.rag.tenant.service.impl.UserServiceImpl;
import com.company.rag.web.model.UserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用户服务单元测试
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    
    @Mock
    private UserMapper userMapper;
    
    @Mock
    private UserTenantRelMapper userTenantRelMapper;
    
    @Mock
    private TenantService tenantService;
    
    @InjectMocks
    private UserServiceImpl userService;
    
    private UserDTO.CreateRequest createRequest;
    private UserDTO.UpdateRequest updateRequest;
    
    @BeforeEach
    void setUp() {
        createRequest = new UserDTO.CreateRequest();
        createRequest.setUsername("testuser");
        createRequest.setPassword("password123");
        createRequest.setDisplayName("测试用户");
        createRequest.setEmail("test@example.com");
        createRequest.setRole("user");
        createRequest.setTenantIds(Arrays.asList(1L, 2L));
        
        updateRequest = new UserDTO.UpdateRequest();
        updateRequest.setDisplayName("更新后的用户");
        updateRequest.setEmail("updated@example.com");
        updateRequest.setRole("admin");
        updateRequest.setTenantIds(Arrays.asList(1L));
    }
    
    @Test
    void testCreateUser_Success() {
        // Arrange
        when(userMapper.countByUsername("testuser")).thenReturn(0);
        
        Tenant tenant1 = new Tenant();
        tenant1.setId(1L);
        Tenant tenant2 = new Tenant();
        tenant2.setId(2L);
        when(tenantService.getById(1L)).thenReturn(tenant1);
        when(tenantService.getById(2L)).thenReturn(tenant2);
        
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            return 1;
        });
        
        when(userTenantRelMapper.findTenantIdsByUserId(1L)).thenReturn(Arrays.asList(1L, 2L));
        when(userTenantRelMapper.findTenantNamesByUserId(1L)).thenReturn(Arrays.asList("租户 1", "租户 2"));
        
        // Act
        UserDTO.UserResponse response = userService.createUser(createRequest);
        
        // Assert
        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("testuser", response.getUsername());
        assertEquals("user", response.getRole());
        
        verify(userMapper).insert(any(User.class));
        verify(userTenantRelMapper).batchInsert(eq(1L), eq(Arrays.asList(1L, 2L)));
    }
    
    @Test
    void testCreateUser_UsernameExists() {
        // Arrange
        when(userMapper.countByUsername("testuser")).thenReturn(1);
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> userService.createUser(createRequest)
        );
        assertTrue(exception.getMessage().contains("用户名已存在"));
    }
    
    @Test
    void testCreateUser_InvalidRole() {
        // Arrange
        createRequest.setRole("invalid_role");
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> userService.createUser(createRequest)
        );
        assertTrue(exception.getMessage().contains("无效的角色"));
    }
    
    @Test
    void testCreateUser_TenantNotFound() {
        // Arrange
        when(userMapper.countByUsername("testuser")).thenReturn(0);
        when(tenantService.getById(999L)).thenReturn(null);
        createRequest.setTenantIds(Arrays.asList(999L));
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> userService.createUser(createRequest)
        );
        assertTrue(exception.getMessage().contains("租户不存在"));
    }
    
    @Test
    void testQueryUserList_WithFilters() {
        // Arrange
        User user1 = new User();
        user1.setId(1L);
        user1.setUsername("user1");
        user1.setRole("user");
        user1.setStatus(1);
        
        User user2 = new User();
        user2.setId(2L);
        user2.setUsername("user2");
        user2.setRole("admin");
        user2.setStatus(1);
        
        when(userMapper.findByTenantId(1L, 1)).thenReturn(Arrays.asList(user1, user2));
        when(userTenantRelMapper.findTenantIdsByUserId(1L)).thenReturn(Arrays.asList(1L));
        when(userTenantRelMapper.findTenantNamesByUserId(1L)).thenReturn(Arrays.asList("租户 1"));
        when(userTenantRelMapper.findTenantIdsByUserId(2L)).thenReturn(Arrays.asList(1L));
        when(userTenantRelMapper.findTenantNamesByUserId(2L)).thenReturn(Arrays.asList("租户 1"));
        
        // Act
        List<UserDTO.UserResponse> users = userService.queryUserList("user", 1L, 1, null);
        
        // Assert
        assertEquals(2, users.size());
        verify(userMapper).findByTenantId(1L, 1);
    }
    
    @Test
    void testGetUserById_Success() {
        // Arrange
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setRole("user");
        user.setStatus(1);
        
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userTenantRelMapper.findTenantIdsByUserId(1L)).thenReturn(Arrays.asList(1L));
        
        // Act
        UserDTO.UserDetailResponse response = userService.getUserById(1L);
        
        // Assert
        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("testuser", response.getUsername());
    }
    
    @Test
    void testGetUserById_NotFound() {
        // Arrange
        when(userMapper.selectById(999L)).thenReturn(null);
        
        // Act
        UserDTO.UserDetailResponse response = userService.getUserById(999L);
        
        // Assert
        assertNull(response);
    }
    
    @Test
    void testUpdateUser_Success() {
        // Arrange
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setRole("user");
        user.setStatus(1);
        
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(userTenantRelMapper.findTenantIdsByUserId(1L)).thenReturn(Arrays.asList(1L));
        when(userTenantRelMapper.findTenantNamesByUserId(1L)).thenReturn(Arrays.asList("租户 1"));
        
        // Act
        UserDTO.UserResponse response = userService.updateUser(1L, updateRequest);
        
        // Assert
        assertNotNull(response);
        verify(userMapper).updateById(any(User.class));
        verify(userTenantRelMapper).deleteByUserId(1L);
        verify(userTenantRelMapper).batchInsert(eq(1L), eq(Arrays.asList(1L)));
    }
    
    @Test
    void testUpdateUser_NotFound() {
        // Arrange
        when(userMapper.selectById(999L)).thenReturn(null);
        
        // Act & Assert
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> userService.updateUser(999L, updateRequest)
        );
        assertTrue(exception.getMessage().contains("用户不存在"));
    }
    
    @Test
    void testDeleteUser_Success() {
        // Arrange
        User user = new User();
        user.setId(1L);
        
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userMapper.deleteById(1L)).thenReturn(1);
        
        // Act
        boolean result = userService.deleteUser(1L);
        
        // Assert
        assertTrue(result);
        verify(userTenantRelMapper).deleteByUserId(1L);
        verify(userMapper).deleteById(1L);
    }
    
    @Test
    void testDeleteUser_NotFound() {
        // Arrange
        when(userMapper.selectById(999L)).thenReturn(null);
        
        // Act
        boolean result = userService.deleteUser(999L);
        
        // Assert
        assertFalse(result);
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn test -pl company-rag-tenant -Dtest=UserServiceTest`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 3: 提交**

```bash
git add company-rag-tenant/src/test/java/com/company/rag/tenant/service/UserServiceTest.java
git commit -m "test: 添加用户服务单元测试
- 测试创建用户成功场景
- 测试用户名重复、角色无效、租户不存在等异常场景
- 测试查询用户列表 (带筛选)
- 测试查询用户详情
- 测试更新用户
- 测试删除用户
- 使用 Mockito 模拟依赖"
```

---

### Task 6: 编写 UserController 集成测试

**Files:**
- Create: `company-rag-web/src/test/java/com/company/rag/web/controller/UserControllerTest.java`

- [ ] **Step 1: 创建 UserController 集成测试**

```java
package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.tenant.service.UserService;
import com.company.rag.web.model.UserDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 用户控制器集成测试
 */
@WebMvcTest(UserController.class)
class UserControllerTest {
    
    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @MockBean
    private UserService userService;
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testCreateUser_Success() throws Exception {
        // Arrange
        UserDTO.CreateRequest request = new UserDTO.CreateRequest();
        request.setUsername("testuser");
        request.setPassword("password123");
        request.setDisplayName("测试用户");
        request.setEmail("test@example.com");
        request.setRole("user");
        request.setTenantIds(Arrays.asList(1L, 2L));
        
        UserDTO.UserResponse response = new UserDTO.UserResponse();
        response.setId(1L);
        response.setUsername("testuser");
        response.setRole("user");
        
        when(userService.createUser(any(UserDTO.CreateRequest.class))).thenReturn(response);
        
        // Act & Assert
        mockMvc.perform(post("/api/user")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.username").value("testuser"));
        
        verify(userService).createUser(any(UserDTO.CreateRequest.class));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testCreateUser_Forbidden() throws Exception {
        // Arrange
        UserDTO.CreateRequest request = new UserDTO.CreateRequest();
        request.setUsername("testuser");
        request.setPassword("password123");
        request.setDisplayName("测试用户");
        request.setRole("user");
        request.setTenantIds(Arrays.asList(1L));
        
        // Act & Assert - 非管理员应该被拒绝
        mockMvc.perform(post("/api/user")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testListUsers_Success() throws Exception {
        // Arrange
        List<UserDTO.UserResponse> users = Arrays.asList(
            createUserResponse(1L, "user1", "user"),
            createUserResponse(2L, "user2", "admin")
        );
        
        when(userService.queryUserList(null, null, null, null)).thenReturn(users);
        
        // Act & Assert
        mockMvc.perform(get("/api/user/list")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testListUsers_WithFilters() throws Exception {
        // Arrange
        List<UserDTO.UserResponse> users = Arrays.asList(
            createUserResponse(1L, "user1", "user")
        );
        
        when(userService.queryUserList("user", 1L, 1, "user1")).thenReturn(users);
        
        // Act & Assert
        mockMvc.perform(get("/api/user/list")
                .with(csrf())
                .param("role", "user")
                .param("tenantId", "1")
                .param("status", "1")
                .param("username", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        
        verify(userService).queryUserList("user", 1L, 1, "user1");
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testGetUserById_Success() throws Exception {
        // Arrange
        UserDTO.UserDetailResponse response = new UserDTO.UserDetailResponse();
        response.setId(1L);
        response.setUsername("testuser");
        response.setRole("user");
        
        when(userService.getUserById(1L)).thenReturn(response);
        
        // Act & Assert
        mockMvc.perform(get("/api/user/1")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testGetUserById_NotFound() throws Exception {
        // Arrange
        when(userService.getUserById(999L)).thenReturn(null);
        
        // Act & Assert
        mockMvc.perform(get("/api/user/999")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testUpdateUser_Success() throws Exception {
        // Arrange
        UserDTO.UpdateRequest request = new UserDTO.UpdateRequest();
        request.setDisplayName("Updated User");
        request.setRole("admin");
        request.setTenantIds(Arrays.asList(1L));
        
        UserDTO.UserResponse response = new UserDTO.UserResponse();
        response.setId(1L);
        response.setDisplayName("Updated User");
        
        when(userService.updateUser(eq(1L), any(UserDTO.UpdateRequest.class))).thenReturn(response);
        
        // Act & Assert
        mockMvc.perform(put("/api/user/1")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.displayName").value("Updated User"));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testDeleteUser_Success() throws Exception {
        // Arrange
        when(userService.deleteUser(1L)).thenReturn(true);
        
        // Act & Assert
        mockMvc.perform(delete("/api/user/1")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }
    
    @Test
    @WithMockUser(roles = "ADMIN")
    void testDeleteUser_NotFound() throws Exception {
        // Arrange
        when(userService.deleteUser(999L)).thenReturn(false);
        
        // Act & Assert
        mockMvc.perform(delete("/api/user/999")
                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
    
    private UserDTO.UserResponse createUserResponse(Long id, String username, String role) {
        UserDTO.UserResponse response = new UserDTO.UserResponse();
        response.setId(id);
        response.setUsername(username);
        response.setRole(role);
        response.setTenantIds(Arrays.asList(1L));
        response.setTenantNames(Arrays.asList("租户 1"));
        return response;
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn test -pl company-rag-web -Dtest=UserControllerTest`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 3: 提交**

```bash
git add company-rag-web/src/test/java/com/company/rag/web/controller/UserControllerTest.java
git commit -m "test: 添加用户控制器集成测试
- 测试创建用户 API
- 测试权限控制 (非管理员禁止访问)
- 测试查询用户列表 API (带筛选参数)
- 测试查询用户详情 API
- 测试更新用户 API
- 测试删除用户 API
- 使用@WithMockUser 模拟管理员角色"
```

---

### Task 7: 前端实现 - 用户管理 UI 框架

**Files:**
- Modify: `company-rag-web/src/main/resources/templates/index.html`

- [ ] **Step 1: 添加用户管理导航按钮**

在 index.html 的导航栏部分 (约第 108 行) 添加用户管理按钮:

```html
<!-- 在现有的租户按钮后面添加 -->
<button class="hdr-btn" :class="{ active: currentTab === 'tenant' }" @click="currentTab = 'tenant'">🏢 租户</button>
<button class="hdr-btn" 
        v-if="role === 'admin'"
        :class="{ active: currentTab === 'user' }" 
        @click="currentTab = 'user'">
    👤 用户
</button>
<button class="hdr-btn" @click="showUpload = true">📤 上传文件</button>
```

- [ ] **Step 2: 添加用户管理数据变量**

在 Vue setup() 函数中添加用户管理相关变量 (约第 374 行，租户管理变量后面):

```javascript
// 租户管理相关
const currentTab = ref('chat'); // 'chat' or 'tenant' or 'user'
const tenants = ref([]);
const loadingTenants = ref(false);
const creating = ref(false);
const tenantForm = ref({
    tenantCode: '',
    tenantName: '',
    contactName: '',
    contactPhone: ''
});

// 用户管理相关
const users = ref([]);
const loadingUsers = ref(false);
const creatingUser = ref(false);
const editingUserId = ref(null);
const showEditDialog = ref(false);
const userForm = ref({
    username: '',
    password: '',
    displayName: '',
    email: '',
    role: 'user',
    tenantIds: []
});

// 筛选条件
const userFilters = ref({
    role: '',
    tenantId: '',
    status: '',
    username: ''
});
```

- [ ] **Step 3: 添加用户管理方法**

在 Vue setup() 函数的 return 之前添加用户管理方法:

```javascript
// ========== 用户管理相关函数 ==========

async function loadUsers() {
    loadingUsers.value = true;
    try {
        const params = new URLSearchParams();
        if (userFilters.value.role) params.append('role', userFilters.value.role);
        if (userFilters.value.tenantId) params.append('tenantId', userFilters.value.tenantId);
        if (userFilters.value.status) params.append('status', userFilters.value.status);
        if (userFilters.value.username) params.append('username', userFilters.value.username);
        
        const json = await apiRequest('/api/user/list?' + params.toString());
        if (json && json.code === 200) {
            users.value = json.data || [];
        } else if (json) {
            ElementPlus.ElMessage.error('加载用户列表失败：' + (json.msg || ''));
        }
    } catch(e) {
        console.error('加载用户列表失败', e);
        ElementPlus.ElMessage.error('加载用户列表失败：' + e.message);
    } finally {
        loadingUsers.value = false;
    }
}

async function createUser() {
    if (!userForm.value.username || !userForm.value.password || 
        !userForm.value.displayName || !userForm.value.role || 
        !userForm.value.tenantIds || userForm.value.tenantIds.length === 0) {
        ElementPlus.ElMessage.warning('请填写必填项，并至少选择一个租户');
        return;
    }
    
    creatingUser.value = true;
    try {
        const json = await apiRequest('/api/user', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(userForm.value)
        });
        if (json && json.code === 200) {
            ElementPlus.ElMessage.success('用户创建成功');
            resetUserForm();
            loadUsers();
        } else if (json) {
            ElementPlus.ElMessage.error('创建用户失败：' + (json.msg || ''));
        }
    } catch(e) {
        console.error('创建用户失败', e);
        ElementPlus.ElMessage.error('创建用户失败：' + e.message);
    } finally {
        creatingUser.value = false;
    }
}

async function updateUser() {
    if (!editingUserId.value) return;
    
    if (!userForm.value.displayName || !userForm.value.role || 
        !userForm.value.tenantIds || userForm.value.tenantIds.length === 0) {
        ElementPlus.ElMessage.warning('请填写必填项，并至少选择一个租户');
        return;
    }
    
    creatingUser.value = true;
    try {
        const json = await apiRequest('/api/user/' + editingUserId.value, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(userForm.value)
        });
        if (json && json.code === 200) {
            ElementPlus.ElMessage.success('用户更新成功');
            showEditDialog.value = false;
            loadUsers();
        } else if (json) {
            ElementPlus.ElMessage.error('更新用户失败：' + (json.msg || ''));
        }
    } catch(e) {
        console.error('更新用户失败', e);
        ElementPlus.ElMessage.error('更新用户失败：' + e.message);
    } finally {
        creatingUser.value = false;
    }
}

async function editUser(user) {
    editingUserId.value = user.id;
    userForm.value = {
        username: user.username,
        password: '', // 留空表示不修改
        displayName: user.displayName,
        email: user.email,
        role: user.role,
        tenantIds: [...user.tenantIds]
    };
    showEditDialog.value = true;
}

async function deleteUser(user) {
    try {
        await ElementPlus.ElMessageBox.confirm(
            `确定要删除用户「${user.displayName}」（${user.username}）吗？`,
            '删除确认',
            { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' }
        );
    } catch {
        return;
    }
    
    try {
        const json = await apiRequest('/api/user/' + user.id, {
            method: 'DELETE'
        });
        if (json && json.code === 200) {
            ElementPlus.ElMessage.success('用户已删除');
            loadUsers();
        } else if (json) {
            ElementPlus.ElMessage.error('删除失败：' + (json.msg || ''));
        }
    } catch(e) {
        console.error('删除用户失败', e);
        ElementPlus.ElMessage.error('删除用户失败：' + e.message);
    }
}

function resetUserForm() {
    userForm.value = {
        username: '',
        password: '',
        displayName: '',
        email: '',
        role: 'user',
        tenantIds: []
    };
}

function clearUserFilters() {
    userFilters.value = {
        role: '',
        tenantId: '',
        status: '',
        username: ''
    };
    loadUsers();
}
```

- [ ] **Step 4: 更新 return 语句**

在 Vue setup() 的 return 中添加用户管理相关变量和方法:

```javascript
return {
    messages, userInput, isLoading, documents, selectedDocId,
    showUpload, showMetrics, splitStrategy, currentTenantId, role, displayName, chatContainer,
    metrics, uploadUrl, uploadHeaders, formatSize, renderMarkdown,
    sendMessage, loadDocuments, beforeUpload, onUploadSuccess,
    deleteDocument,
    // 会话管理
    sessions, currentSessionId, createNewSession, switchSession, deleteSession, formatTime,
    // 租户管理
    currentTab, tenants, loadingTenants, creating, tenantForm,
    loadTenants, createTenant, resetForm, viewTenantDetail, selectTenant, deleteTenant,
    // 用户管理
    users, loadingUsers, creatingUser, editingUserId, showEditDialog, userForm, userFilters,
    loadUsers, createUser, updateUser, editUser, deleteUser, resetUserForm, clearUserFilters,
    // 高级模式
    chatMode, showAdvanced,
    // 认证
    handleLogout
};
```

- [ ] **Step 5: 提交**

```bash
git add company-rag-web/src/main/resources/templates/index.html
git commit -m "feat: 添加用户管理 UI 框架
- 添加用户管理导航按钮 (仅管理员可见)
- 添加用户管理数据变量和方法
- 实现加载用户列表、创建、更新、删除功能
- 实现筛选功能"
```

---

### Task 8: 前端实现 - 用户管理界面

**Files:**
- Modify: `company-rag-web/src/main/resources/templates/index.html`

- [ ] **Step 1: 添加用户管理区域 HTML**

在 index.html 的租户管理区域后面 (约第 221 行) 添加用户管理区域:

```html
<!-- 用户管理区域 -->
<div v-if="currentTab === 'user'" class="user-management" style="flex: 1; overflow-y: auto; padding: 20px; background: white;">
    <h2 style="margin-bottom: 20px; color: #303133;">👤 用户管理</h2>
    
    <!-- 筛选区 -->
    <el-card style="margin-bottom: 20px;">
        <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
                <span style="font-weight: 600;">筛选条件</span>
                <el-button size="small" @click="clearUserFilters">清空筛选</el-button>
            </div>
        </template>
        <el-form :inline="true" :model="userFilters">
            <el-form-item label="角色">
                <el-select v-model="userFilters.role" placeholder="全部角色" clearable size="small">
                    <el-option label="管理员" value="admin" />
                    <el-option label="普通用户" value="user" />
                    <el-option label="访客" value="viewer" />
                </el-select>
            </el-form-item>
            <el-form-item label="租户">
                <el-select v-model="userFilters.tenantId" placeholder="全部租户" clearable size="small">
                    <el-option v-for="t in tenants" :key="t.id" :label="t.tenantName" :value="t.id" />
                </el-select>
            </el-form-item>
            <el-form-item label="状态">
                <el-select v-model="userFilters.status" placeholder="全部状态" clearable size="small">
                    <el-option label="启用" :value="1" />
                    <el-option label="禁用" :value="0" />
                </el-select>
            </el-form-item>
            <el-form-item label="用户名">
                <el-input v-model="userFilters.username" placeholder="模糊搜索" 
                         size="small" clearable style="width: 150px;" />
            </el-form-item>
            <el-form-item>
                <el-button type="primary" size="small" @click="loadUsers">查询</el-button>
            </el-form-item>
        </el-form>
    </el-card>
    
    <!-- 创建用户表单 -->
    <el-card style="margin-bottom: 20px;">
        <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
                <span style="font-weight: 600;">创建用户</span>
            </div>
        </template>
        <el-form :model="userForm" label-width="100px" class="user-form">
            <el-row :gutter="20">
                <el-col :span="12">
                    <el-form-item label="用户名" required>
                        <el-input v-model="userForm.username" placeholder="登录用户名" style="width: 100%;"></el-input>
                    </el-form-item>
                </el-col>
                <el-col :span="12">
                    <el-form-item label="密码" required>
                        <el-input v-model="userForm.password" type="password" placeholder="初始密码" 
                                 style="width: 100%;"></el-input>
                    </el-form-item>
                </el-col>
            </el-row>
            <el-row :gutter="20">
                <el-col :span="12">
                    <el-form-item label="显示名" required>
                        <el-input v-model="userForm.displayName" placeholder="用户昵称" style="width: 100%;"></el-input>
                    </el-form-item>
                </el-col>
                <el-col :span="12">
                    <el-form-item label="邮箱">
                        <el-input v-model="userForm.email" type="email" placeholder="可选" style="width: 100%;"></el-input>
                    </el-form-item>
                </el-col>
            </el-row>
            <el-row :gutter="20">
                <el-col :span="12">
                    <el-form-item label="角色" required>
                        <el-select v-model="userForm.role" style="width: 100%;" size="small">
                            <el-option label="管理员" value="admin" />
                            <el-option label="普通用户" value="user" />
                            <el-option label="访客" value="viewer" />
                        </el-select>
                    </el-form-item>
                </el-col>
                <el-col :span="12">
                    <el-form-item label="关联租户" required>
                        <el-select v-model="userForm.tenantIds" multiple collapse-tags collapse-tags-tooltip
                                  placeholder="请选择租户" style="width: 100%;" size="small">
                            <el-option v-for="t in tenants" :key="t.id" :label="t.tenantName" :value="t.id" />
                        </el-select>
                    </el-form-item>
                </el-col>
            </el-row>
            <el-form-item>
                <el-button type="primary" @click="createUser" :loading="creatingUser">创建用户</el-button>
                <el-button @click="resetUserForm">重置</el-button>
            </el-form-item>
        </el-form>
    </el-card>
    
    <!-- 用户列表 -->
    <el-card>
        <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
                <span style="font-weight: 600;">用户列表</span>
                <el-button size="small" @click="loadUsers">🔄 刷新</el-button>
            </div>
        </template>
        <div v-loading="loadingUsers" style="position: relative;">
            <table class="native-tenant-table">
                <thead>
                    <tr>
                        <th style="width: 80px;">ID</th>
                        <th style="width: 150px;">用户名</th>
                        <th style="width: 150px;">显示名</th>
                        <th style="width: 200px;">邮箱</th>
                        <th style="width: 100px;">角色</th>
                        <th style="width: 250px;">关联租户</th>
                        <th style="width: 100px;">状态</th>
                        <th style="width: 200px;">操作</th>
                    </tr>
                </thead>
                <tbody>
                    <tr v-for="user in users" :key="user.id">
                        <td>{{ user.id }}</td>
                        <td>{{ user.username }}</td>
                        <td>{{ user.displayName }}</td>
                        <td>{{ user.email || '-' }}</td>
                        <td>
                            <el-tag :type="user.role === 'admin' ? 'danger' : user.role === 'user' ? 'primary' : 'info'" 
                                   size="small">
                                {{ user.role === 'admin' ? '管理员' : user.role === 'user' ? '普通用户' : '访客' }}
                            </el-tag>
                        </td>
                        <td>
                            <span v-if="user.tenantNames && user.tenantNames.length > 0">
                                {{ user.tenantNames.join(', ') }}
                            </span>
                            <span v-else style="color: #999;">-</span>
                        </td>
                        <td>
                            <span :style="{ padding: '2px 8px', borderRadius: '4px', fontSize: '12px', 
                                         backgroundColor: user.status === 1 ? '#f0f9ff' : '#fef0f0', 
                                         color: user.status === 1 ? '#67c23a' : '#f56c6c' }">
                                {{ user.status === 1 ? '启用' : '禁用' }}
                            </span>
                        </td>
                        <td>
                            <button class="table-btn" @click="editUser(user)">编辑</button>
                            <button class="table-btn" style="color:#f56c6c;" @click="deleteUser(user)">删除</button>
                        </td>
                    </tr>
                    <tr v-if="users.length === 0">
                        <td colspan="8" style="text-align: center; padding: 40px; color: #909399;">暂无数据</td>
                    </tr>
                </tbody>
            </table>
        </div>
    </el-card>
</div>

<!-- 编辑用户对话框 -->
<el-dialog v-model="showEditDialog" title="编辑用户" width="500px">
    <el-form :model="userForm" label-width="100px">
        <el-form-item label="用户名">
            <el-input v-model="userForm.username" disabled></el-input>
        </el-form-item>
        <el-form-item label="密码">
            <el-input v-model="userForm.password" type="password" 
                     placeholder="留空表示不修改密码"></el-input>
        </el-form-item>
        <el-form-item label="显示名" required>
            <el-input v-model="userForm.displayName"></el-input>
        </el-form-item>
        <el-form-item label="邮箱">
            <el-input v-model="userForm.email" type="email"></el-input>
        </el-form-item>
        <el-form-item label="角色" required>
            <el-select v-model="userForm.role" style="width: 100%;">
                <el-option label="管理员" value="admin" />
                <el-option label="普通用户" value="user" />
                <el-option label="访客" value="viewer" />
            </el-select>
        </el-form-item>
        <el-form-item label="关联租户" required>
            <el-select v-model="userForm.tenantIds" multiple placeholder="请选择租户" style="width: 100%;">
                <el-option v-for="t in tenants" :key="t.id" :label="t.tenantName" :value="t.id" />
            </el-select>
        </el-form-item>
    </el-form>
    <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" @click="updateUser" :loading="creatingUser">保存</el-button>
    </template>
</el-dialog>
```

- [ ] **Step 2: 在 onMounted 中加载用户列表**

修改 onMounted 函数，添加加载用户列表:

```javascript
onMounted(() => {
    // 已登录，加载数据
    loadDocuments();
    loadSessions();
    loadTenants();
    // 如果是管理员，预加载用户列表
    if (role.value === 'admin') {
        loadUsers();
    }
});
```

- [ ] **Step 3: 提交**

```bash
git add company-rag-web/src/main/resources/templates/index.html
git commit -m "feat: 完成用户管理界面
- 添加筛选区 (角色/租户/状态/用户名)
- 添加创建用户表单
- 添加用户列表表格
- 添加编辑用户对话框
- 复用租户管理表格样式"
```

---

### Task 9: 集成测试与验收

**Files:**
- 无需修改代码

- [ ] **Step 1: 启动应用**

Run: `mvn spring-boot:run -pl company-rag-bootstrap`
Expected: 应用启动成功，监听端口 8080

- [ ] **Step 2: 测试创建用户**

使用管理员账号登录，访问 http://localhost:8080，点击"👤 用户"标签页，创建测试用户:
- 用户名：testuser
- 密码：test123
- 显示名：测试用户
- 邮箱：test@example.com
- 角色：普通用户
- 关联租户：选择默认租户

Expected: 创建成功，用户列表显示新用户

- [ ] **Step 3: 测试筛选功能**

在筛选区选择不同条件:
- 角色：管理员/普通用户/访客
- 租户：选择不同租户
- 状态：启用/禁用
- 用户名：输入部分用户名

Expected: 列表正确过滤

- [ ] **Step 4: 测试编辑用户**

点击"编辑"按钮，修改用户信息:
- 修改显示名、邮箱、角色
- 修改关联租户
- 留空密码 (不修改)

Expected: 更新成功

- [ ] **Step 5: 测试删除用户**

点击"删除"按钮，确认删除

Expected: 删除成功，用户从列表中消失

- [ ] **Step 6: 测试权限控制**

使用普通用户账号登录，验证:
- 看不到"👤 用户"导航按钮
- 直接访问 /api/user/* 接口返回 403

Expected: 权限控制生效

- [ ] **Step 7: 提交**

```bash
git commit --allow-empty -m "chore: 完成用户管理功能集成测试
- 测试创建用户功能
- 测试筛选功能
- 测试编辑用户功能
- 测试删除用户功能
- 测试权限控制
- 所有测试通过"
```

---

### Task 10: 更新 README 文档

**Files:**
- Modify: `README.md`

- [ ] **Step 1: 在 README 中添加用户管理功能说明**

在 README.md 的功能特性部分添加:

```markdown
## 功能特性

### 用户管理
- 👤 用户增删改查 (仅管理员可访问)
- 🔐 角色管理 (管理员/普通用户/访客)
- 🏢 多租户关联 (一个用户可关联多个租户)
- 🔍 用户筛选 (按角色/租户/状态/用户名)
- 📝 审计日志记录

### 租户管理
- 🏢 租户创建与删除
- 📊 Schema 自动初始化
- 👥 用户 - 租户关联管理
```

- [ ] **Step 2: 提交**

```bash
git add README.md
git commit -m "docs: 更新 README 添加用户管理功能说明"
```

---

## 计划自检

**1. Spec 覆盖检查:**
- ✅ 创建用户功能 - Task 1, 3, 4, 7, 8
- ✅ 查询用户列表 - Task 2, 3, 4, 7, 8
- ✅ 查询用户详情 - Task 2, 3, 4
- ✅ 更新用户功能 - Task 1, 3, 4, 7, 8
- ✅ 删除用户功能 - Task 2, 3, 4, 7, 8
- ✅ 权限控制 - Task 4, 6, 8
- ✅ 审计日志 - Task 4
- ✅ 前端 UI - Task 7, 8
- ✅ 单元测试 - Task 5, 6

**2. 占位符扫描:** 无 TBD、TODO 或未完成的步骤

**3. 类型一致性检查:**
- ✅ UserDTO 在所有任务中使用一致
- ✅ UserService 接口与实现一致
- ✅ API 路径与前端调用一致

---
