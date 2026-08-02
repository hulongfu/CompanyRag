# 阶段一：安全与认证体系 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 CompanyRag 建立完整的 JWT 认证、角色级权限控制和关键操作审计日志体系。

**Architecture:** 在 common 模块新增 JWT 工具和安全上下文，在 bootstrap 模块配置 Spring Security 过滤器链，在 tenant 模块新增审计日志表和相关服务，在 web 模块新增认证 Controller。复用现有 User 表的 password 字段（已存 BCrypt 哈希）和 role 字段（admin/user/viewer）。

**Tech Stack:** Spring Security 6 (Spring Boot 3.4 内置), jjwt 0.12.6, BCrypt (Spring Security 内置), Spring AOP, MyBatis-Plus

---

## 文件结构

### 新增文件（7 个）

| 文件 | 职责 |
|------|------|
| `company-rag-common/src/main/java/com/company/rag/common/security/JwtProperties.java` | JWT 配置属性类（密钥、有效期） |
| `company-rag-common/src/main/java/com/company/rag/common/security/JwtTokenProvider.java` | JWT 令牌生成、解析、校验 |
| `company-rag-common/src/main/java/com/company/rag/common/security/SecurityUser.java` | 实现 UserDetails，携带 userId/tenantId/role |
| `company-rag-common/src/main/java/com/company/rag/common/annotation/AuditLog.java` | 审计日志注解 |
| `company-rag-common/src/main/java/com/company/rag/common/aspect/AuditLogAspect.java` | 审计日志 AOP 切面 |
| `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/SecurityConfig.java` | Spring Security 过滤器链配置 |
| `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/JwtAuthenticationFilter.java` | JWT 认证过滤器 |

### 修改文件（7 个）

| 文件 | 变更 |
|------|------|
| `pom.xml` | 添加 jjwt 依赖、spring-boot-starter-security 依赖 |
| `company-rag-common/pom.xml` | 添加 spring-boot-starter-security 依赖 |
| `company-rag-common/src/main/java/com/company/rag/common/exception/GlobalExceptionHandler.java` | 添加 AccessDeniedException 和 AuthenticationException 处理 |
| `company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/UserMapper.java` | 添加按用户名查询方法 |
| `company-rag-tenant/src/main/java/com/company/rag/tenant/service/TenantService.java` | 添加用户认证相关方法 |
| `company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java` | 实现用户认证 + 审计日志写入 |
| `company-rag-web/src/main/java/com/company/rag/web/config/WebMvcConfig.java` | 将 /api/auth/** 加入排除路径 |
| `company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java` | 移除硬编码 userId=1，从 SecurityContext 获取 |
| `.env` | 添加 JWT_SECRET 环境变量 |

---

### Task 1: 添加安全相关 Maven 依赖

**Files:**
- Modify: `pom.xml`
- Modify: `company-rag-common/pom.xml`

- [ ] **Step 1: 在根 pom.xml 的 properties 中添加 jjwt 版本**

```xml
<jjwt.version>0.12.6</jjwt.version>
```

- [ ] **Step 2: 在根 pom.xml 的 dependencyManagement 中添加 jjwt 依赖管理**

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>${jjwt.version}</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>${jjwt.version}</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>${jjwt.version}</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 3: 在 company-rag-common/pom.xml 中添加依赖**

```xml
<!-- JWT -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <scope>runtime</scope>
</dependency>
<!-- Spring Security（common 模块提供 SecurityUser 需要） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

- [ ] **Step 4: 验证编译**

Run: `mvn compile -q -pl company-rag-common`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add pom.xml company-rag-common/pom.xml
git commit -m "build: add jjwt 0.12.6 and spring-security dependencies"
```

---

### Task 2: 创建 JWT 配置属性类

**Files:**
- Create: `company-rag-common/src/main/java/com/company/rag/common/security/JwtProperties.java`

- [ ] **Step 1: 创建 JwtProperties**

```java
package com.company.rag.common.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置属性
 * 从环境变量或配置文件中读取
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** JWT 签名密钥（通过环境变量 JWT_SECRET 注入） */
    private String secret;

    /** Access Token 有效期（毫秒），默认 2 小时 */
    private long accessTokenExpiration = 7200000L;

    /** Refresh Token 有效期（毫秒），默认 7 天 */
    private long refreshTokenExpiration = 604800000L;
}
```

- [ ] **Step 2: Commit**

```bash
git add company-rag-common/src/main/java/com/company/rag/common/security/JwtProperties.java
git commit -m "feat(security): add JwtProperties configuration class"
```

---

### Task 3: 创建 SecurityUser（实现 UserDetails）

**Files:**
- Create: `company-rag-common/src/main/java/com/company/rag/common/security/SecurityUser.java`

- [ ] **Step 1: 创建 SecurityUser**

```java
package com.company.rag.common.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * 安全用户实体，实现 UserDetails
 * 携带 userId、tenantId、role 供 JWT 和权限校验使用
 */
@Getter
public class SecurityUser implements UserDetails {

    private final Long userId;
    private final Long tenantId;
    private final String username;
    private final String password;
    private final String role;
    private final List<GrantedAuthority> authorities;
    private final boolean enabled;

    public SecurityUser(Long userId, Long tenantId, String username, String password,
                        String role, boolean enabled) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.username = username;
        this.password = password;
        this.role = role;
        this.authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        this.enabled = enabled;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q -pl company-rag-common`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add company-rag-common/src/main/java/com/company/rag/common/security/SecurityUser.java
git commit -m "feat(security): add SecurityUser implementing UserDetails"
```

---

### Task 4: 创建 JwtTokenProvider

**Files:**
- Create: `company-rag-common/src/main/java/com/company/rag/common/security/JwtTokenProvider.java`

- [ ] **Step 1: 创建 JwtTokenProvider**

```java
package com.company.rag.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT 令牌提供者
 * 负责生成、解析和校验 JWT Access Token 和 Refresh Token
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;

    /**
     * 生成 Access Token
     *
     * @param userId   用户 ID
     * @param tenantId 租户 ID
     * @param role     用户角色
     * @return JWT 令牌字符串
     */
    public String generateAccessToken(Long userId, Long tenantId, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtProperties.getAccessTokenExpiration());

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tenantId", tenantId)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 生成 Refresh Token
     */
    public String generateRefreshToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtProperties.getRefreshTokenExpiration());

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 从 JWT 中解析用户 ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        return Long.valueOf(claims.getSubject());
    }

    /**
     * 从 JWT 中解析租户 ID
     */
    public Long getTenantIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("tenantId", Long.class);
    }

    /**
     * 从 JWT 中解析角色
     */
    public String getRoleFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("role", String.class);
    }

    /**
     * 校验 JWT 是否有效
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT 校验失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析 JWT，返回 Claims
     */
    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 获取签名密钥
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtProperties.getSecret());
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
```

- [ ] **Step 2: 创建 JwtTokenProvider 单元测试**

File: `company-rag-common/src/test/java/com/company/rag/common/security/JwtTokenProviderTest.java`

```java
package com.company.rag.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {

    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        // 使用测试用 Base64 密钥（至少 256 位 = 32 字节）
        properties.setSecret("dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tZ2VuZXJhdGlvbi0xMjM0NTY=");
        properties.setAccessTokenExpiration(3600000L); // 1 小时
        properties.setRefreshTokenExpiration(86400000L); // 1 天
        tokenProvider = new JwtTokenProvider(properties);
    }

    @Test
    void generateAccessToken_shouldReturnValidToken() {
        String token = tokenProvider.generateAccessToken(1L, 1L, "admin");
        assertNotNull(token);
        assertTrue(tokenProvider.validateToken(token));
        assertEquals(1L, tokenProvider.getUserIdFromToken(token));
        assertEquals(1L, tokenProvider.getTenantIdFromToken(token));
        assertEquals("admin", tokenProvider.getRoleFromToken(token));
    }

    @Test
    void generateRefreshToken_shouldReturnValidToken() {
        String token = tokenProvider.generateRefreshToken(1L);
        assertNotNull(token);
        assertTrue(tokenProvider.validateToken(token));
        assertEquals(1L, tokenProvider.getUserIdFromToken(token));
    }

    @Test
    void validateToken_withInvalidToken_shouldReturnFalse() {
        assertFalse(tokenProvider.validateToken("invalid-token"));
    }

    @Test
    void validateToken_withExpiredToken_shouldReturnFalse() throws Exception {
        JwtProperties shortExp = new JwtProperties();
        shortExp.setSecret("dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tZ2VuZXJhdGlvbi0xMjM0NTY=");
        shortExp.setAccessTokenExpiration(1L); // 1 毫秒
        JwtTokenProvider shortProvider = new JwtTokenProvider(shortExp);

        String token = shortProvider.generateAccessToken(1L, 1L, "admin");
        Thread.sleep(10); // 等待过期
        assertFalse(shortProvider.validateToken(token));
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `mvn test -pl company-rag-common -Dtest=JwtTokenProviderTest -q`
Expected: Tests run: 4, Passed: 4

- [ ] **Step 4: Commit**

```bash
git add company-rag-common/src/main/java/com/company/rag/common/security/JwtTokenProvider.java company-rag-common/src/test/java/com/company/rag/common/security/JwtTokenProviderTest.java
git commit -m "feat(security): add JwtTokenProvider with unit tests"
```

---

### Task 5: 扩展 UserMapper，添加按用户名查询

**Files:**
- Modify: `company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/UserMapper.java`

- [ ] **Step 1: 添加 findByUsername 方法**

```java
package com.company.rag.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.tenant.model.User;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<User> {

    /**
     * 根据用户名查询用户（跨租户，供登录认证使用）
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} LIMIT 1")
    User findByUsername(String username);
}
```

- [ ] **Step 2: Commit**

```bash
git add company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/UserMapper.java
git commit -m "feat(tenant): add findByUsername method to UserMapper"
```

---

### Task 6: 扩展 TenantService，添加用户认证方法

**Files:**
- Modify: `company-rag-tenant/src/main/java/com/company/rag/tenant/service/TenantService.java`
- Modify: `company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`

- [ ] **Step 1: 在 TenantService 接口中添加方法**

```java
package com.company.rag.tenant.service;

import com.company.rag.common.security.SecurityUser;
import com.company.rag.tenant.model.Tenant;
import com.company.rag.tenant.model.User;

import java.util.List;

public interface TenantService {

    // ... 现有方法保持不变 ...

    /**
     * 根据用户名加载安全用户（用于 Spring Security 认证）
     */
    SecurityUser loadSecurityUserByUsername(String username);

    /**
     * 根据用户 ID 加载安全用户（用于 JWT 认证后获取上下文）
     */
    SecurityUser loadSecurityUserById(Long userId);

    /**
     * 记录审计日志
     */
    void recordAuditLog(String actionType, String targetType, String targetId, String detail);
}
```

- [ ] **Step 2: 在 TenantServiceImpl 中添加实现**

```java
// 在类上添加
import com.company.rag.common.security.SecurityUser;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// 注入 PasswordEncoder
private final PasswordEncoder passwordEncoder;

// 如果 TenantServiceImpl 构造函数是 @RequiredArgsConstructor，需要在构造器中添加
// 或者直接注入字段

/**
 * 根据用户名加载安全用户
 */
@Override
public SecurityUser loadSecurityUserByUsername(String username) {
    User user = userMapper.findByUsername(username);
    if (user == null) {
        throw BizException.unauthorized("用户名或密码错误");
    }
    if (user.getStatus() == null || user.getStatus() != 1) {
        throw BizException.unauthorized("账户已被禁用");
    }
    return new SecurityUser(
            user.getId(),
            user.getTenantId(),
            user.getUsername(),
            user.getPassword(),
            user.getRole(),
            user.getStatus() == 1
    );
}

/**
 * 根据用户 ID 加载安全用户
 */
@Override
public SecurityUser loadSecurityUserById(Long userId) {
    User user = userMapper.selectById(userId);
    if (user == null) {
        throw BizException.unauthorized("用户不存在");
    }
    return new SecurityUser(
            user.getId(),
            user.getTenantId(),
            user.getUsername(),
            user.getPassword(),
            user.getRole(),
            user.getStatus() == 1
    );
}
```

- [ ] **Step 3: 创建 PasswordEncoder Bean 配置类**

File: `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/PasswordEncoderConfig.java`

```java
package com.company.rag.bootstrap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add company-rag-tenant/src/main/java/com/company/rag/tenant/service/TenantService.java company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/PasswordEncoderConfig.java
git commit -m "feat(security): add user authentication methods to TenantService"
```

---

### Task 7: 创建 JWT 认证过滤器

**Files:**
- Create: `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/JwtAuthenticationFilter.java`

- [ ] **Step 1: 创建 JwtAuthenticationFilter**

```java
package com.company.rag.bootstrap.config;

import com.company.rag.common.security.JwtTokenProvider;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.service.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器
 * 从请求头中提取 JWT，解析用户信息并设置到 SecurityContext
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final TenantService tenantService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            try {
                Long userId = jwtTokenProvider.getUserIdFromToken(token);
                Long tenantId = jwtTokenProvider.getTenantIdFromToken(token);
                String role = jwtTokenProvider.getRoleFromToken(token);

                // 构建 SecurityUser（不含密码，用于认证后上下文）
                SecurityUser securityUser = new SecurityUser(
                        userId, tenantId, "", "", role, true
                );

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                securityUser, null, securityUser.getAuthorities()
                        );

                SecurityContextHolder.getContext().setAuthentication(authentication);

                // 同时设置租户上下文（兼容现有 TenantInterceptor）
                TenantContext.setTenantId(tenantId);
                TenantContext.setUserId(userId);

                log.debug("JWT 认证成功: userId={}, tenantId={}, role={}", userId, tenantId, role);
            } catch (Exception e) {
                log.warn("JWT 认证处理异常: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 从请求头中提取 JWT Token
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/JwtAuthenticationFilter.java
git commit -m "feat(security): add JWT authentication filter"
```

---

### Task 8: 创建 Spring Security 配置

**Files:**
- Create: `company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/SecurityConfig.java`

- [ ] **Step 1: 创建 SecurityConfig**

```java
package com.company.rag.bootstrap.config;

import com.company.rag.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 安全配置
 * - 无状态 JWT 认证
 * - 角色级权限控制（通过 @PreAuthorize）
 * - 公共接口免认证
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final TenantService tenantService;
    private final PasswordEncoder passwordEncoder;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 禁用 CSRF（无状态 API 不需要）
            .csrf(csrf -> csrf.disable())
            // 无状态会话
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 放行公共接口
            .authorizeHttpRequests(auth -> auth
                // 认证相关接口放行
                .requestMatchers("/api/auth/**").permitAll()
                // 静态资源和 Swagger 放行
                .requestMatchers("/static/**", "/assets/**", "/css/**", "/js/**",
                    "/favicon.ico", "/webjars/**", "/api-docs/**", "/swagger-ui/**").permitAll()
                // 前端页面放行
                .requestMatchers("/", "/index.html", "/login.html").permitAll()
                // 健康检查放行
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // 其他所有请求需要认证
                .anyRequest().authenticated()
            )
            // 异常处理
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(401);
                    response.getWriter().write(
                        "{\"code\":401,\"msg\":\"未登录或登录已过期\",\"data\":null}");
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setContentType("application/json;charset=UTF-8");
                    response.setStatus(403);
                    response.getWriter().write(
                        "{\"code\":403,\"msg\":\"权限不足\",\"data\":null}");
                })
            )
            // JWT 过滤器在 UsernamePasswordAuthenticationFilter 之前
            .addFilterBefore(jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            try {
                return tenantService.loadSecurityUserByUsername(username);
            } catch (Exception e) {
                throw new UsernameNotFoundException("用户不存在: " + username);
            }
        };
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add company-rag-bootstrap/src/main/java/com/company/rag/bootstrap/config/SecurityConfig.java
git commit -m "feat(security): add Spring Security config with JWT auth"
```

---

### Task 9: 创建 AuthController（登录/刷新/登出）

**Files:**
- Create: `company-rag-web/src/main/java/com/company/rag/web/controller/AuthController.java`
- Create: `company-rag-web/src/main/java/com/company/rag/web/model/AuthRequest.java`
- Create: `company-rag-web/src/main/java/com/company/rag/web/model/AuthResponse.java`

- [ ] **Step 1: 创建 AuthRequest DTO**

```java
package com.company.rag.web.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 认证请求
 */
@Data
public class AuthRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}
```

- [ ] **Step 2: 创建 AuthResponse DTO**

```java
package com.company.rag.web.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 认证响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    private String token;
    private String refreshToken;
    private long expireIn;
    private Long userId;
    private Long tenantId;
    private String role;
    private String displayName;
}
```

- [ ] **Step 3: 创建 AuthController**

```java
package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.common.security.JwtProperties;
import com.company.rag.common.security.JwtTokenProvider;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.tenant.service.TenantService;
import com.company.rag.web.model.AuthRequest;
import com.company.rag.web.model.AuthResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 认证 Controller
 * 提供登录、刷新令牌、登出接口
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtProperties jwtProperties;
    private final TenantService tenantService;

    /**
     * 登录
     */
    @PostMapping("/login")
    public R<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        log.info("用户登录: {}", request.getUsername());

        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername(), request.getPassword())
            );

            SecurityUser securityUser = (SecurityUser) authentication.getPrincipal();

            String accessToken = jwtTokenProvider.generateAccessToken(
                    securityUser.getUserId(),
                    securityUser.getTenantId(),
                    securityUser.getRole()
            );
            String refreshToken = jwtTokenProvider.generateRefreshToken(
                    securityUser.getUserId()
            );

            // 记录审计日志
            tenantService.recordAuditLog(
                    "LOGIN_SUCCESS", "user", String.valueOf(securityUser.getUserId()),
                    "用户登录成功: " + request.getUsername()
            );

            AuthResponse response = AuthResponse.builder()
                    .token(accessToken)
                    .refreshToken(refreshToken)
                    .expireIn(jwtProperties.getAccessTokenExpiration())
                    .userId(securityUser.getUserId())
                    .tenantId(securityUser.getTenantId())
                    .role(securityUser.getRole())
                    .displayName(request.getUsername())
                    .build();

            log.info("用户登录成功: userId={}, tenantId={}, role={}",
                    securityUser.getUserId(), securityUser.getTenantId(), securityUser.getRole());

            return R.ok(response);
        } catch (BadCredentialsException e) {
            log.warn("登录失败: 用户名或密码错误 - {}", request.getUsername());
            tenantService.recordAuditLog(
                    "LOGIN_FAILED", "user", null,
                    "登录失败: " + request.getUsername()
            );
            return R.fail(401, "用户名或密码错误");
        }
    }

    /**
     * 刷新令牌
     */
    @PostMapping("/refresh")
    public R<AuthResponse> refresh(@RequestHeader("Authorization") String bearerToken) {
        String refreshToken = bearerToken.replace("Bearer ", "");

        if (!jwtTokenProvider.validateToken(refreshToken)) {
            return R.fail(401, "刷新令牌已过期");
        }

        Long userId = jwtTokenProvider.getUserIdFromToken(refreshToken);
        SecurityUser securityUser = tenantService.loadSecurityUserById(userId);

        String newAccessToken = jwtTokenProvider.generateAccessToken(
                securityUser.getUserId(),
                securityUser.getTenantId(),
                securityUser.getRole()
        );

        AuthResponse response = AuthResponse.builder()
                .token(newAccessToken)
                .refreshToken(refreshToken)
                .expireIn(jwtProperties.getAccessTokenExpiration())
                .userId(securityUser.getUserId())
                .tenantId(securityUser.getTenantId())
                .role(securityUser.getRole())
                .build();

        return R.ok(response);
    }

    /**
     * 登出（客户端清除 Token 即可，服务端无状态）
     * 后续可扩展 Redis 黑名单机制
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        // 无状态 JWT，客户端清除 Token 即可
        // 后续可添加 Redis 黑名单
        return R.ok();
    }
}
```

- [ ] **Step 4: 修改 WebMvcConfig，将 /api/auth/** 加入排除路径**

```java
// 在 addInterceptors 方法中修改 excludePathPatterns
registry.addInterceptor(new TenantInterceptor(tenantContextHelper, tenantMapper))
        .addPathPatterns("/**")
        .excludePathPatterns("/api/auth/**", "/login", "/css/**", "/js/**",
                "/favicon.ico", "/static/**", "/assets/**", "/webjars/**");
```

- [ ] **Step 5: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/controller/AuthController.java company-rag-web/src/main/java/com/company/rag/web/model/AuthRequest.java company-rag-web/src/main/java/com/company/rag/web/model/AuthResponse.java company-rag-web/src/main/java/com/company/rag/web/config/WebMvcConfig.java
git commit -m "feat(security): add AuthController with login/refresh/logout"
```

---

### Task 10: 更新 GlobalExceptionHandler，处理认证和授权异常

**Files:**
- Modify: `company-rag-common/src/main/java/com/company/rag/common/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: 添加认证和授权异常处理**

```java
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

// 在类中添加

/**
 * 权限不足异常
 */
@ExceptionHandler(AccessDeniedException.class)
@ResponseStatus(HttpStatus.FORBIDDEN)
public R<Void> handleAccessDeniedException(AccessDeniedException e, HttpServletRequest request) {
    log.warn("权限不足: path={} | msg={}", request.getRequestURI(), e.getMessage());
    return R.fail(403, "权限不足");
}

/**
 * 认证异常
 */
@ExceptionHandler(AuthenticationException.class)
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public R<Void> handleAuthenticationException(AuthenticationException e, HttpServletRequest request) {
    log.warn("认证失败: path={} | msg={}", request.getRequestURI(), e.getMessage());
    return R.fail(401, "未登录或登录已过期");
}
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add company-rag-common/src/main/java/com/company/rag/common/exception/GlobalExceptionHandler.java
git commit -m "feat(security): handle AccessDeniedException and AuthenticationException in GlobalExceptionHandler"
```

---

### Task 11: 创建审计日志注解和 AOP 切面

**Files:**
- Create: `company-rag-common/src/main/java/com/company/rag/common/annotation/AuditLog.java`
- Create: `company-rag-common/src/main/java/com/company/rag/common/aspect/AuditLogAspect.java`

- [ ] **Step 1: 创建 @AuditLog 注解**

```java
package com.company.rag.common.annotation;

import java.lang.annotation.*;

/**
 * 审计日志注解
 * 标注在需要记录审计日志的方法上
 * 通过 AOP 自动记录操作日志
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /** 操作类型（如 DOCUMENT_DELETE、TENANT_CREATE） */
    String action();

    /** 目标类型（如 document、tenant、cache） */
    String targetType() default "";

    /** 目标 ID 的 SpEL 表达式（从方法参数中提取） */
    String targetId() default "";

    /** 操作详情的 SpEL 表达式 */
    String detail() default "";
}
```

- [ ] **Step 2: 创建 AuditLogAspect**

```java
package com.company.rag.common.aspect;

import com.company.rag.common.annotation.AuditLog;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.service.TenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 审计日志 AOP 切面
 * 在 @AuditLog 标注的方法执行后，异步记录审计日志
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final TenantService tenantService;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        // 先执行原方法
        Object result = joinPoint.proceed();

        try {
            // 方法执行成功后异步记录审计日志
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            String targetId = resolveSpel(auditLog.targetId(), method, joinPoint.getArgs());
            String detail = resolveSpel(auditLog.detail(), method, joinPoint.getArgs());

            tenantService.recordAuditLog(
                    auditLog.action(),
                    auditLog.targetType(),
                    targetId,
                    detail
            );
        } catch (Exception e) {
            // 审计日志失败不影响主流程
            log.warn("记录审计日志异常: {}", e.getMessage());
        }

        return result;
    }

    /**
     * 解析 SpEL 表达式
     */
    private String resolveSpel(String expression, Method method, Object[] args) {
        if (expression == null || expression.isEmpty()) {
            return "";
        }
        if (!expression.contains("#")) {
            return expression; // 非 SpEL 表达式，直接返回
        }
        StandardEvaluationContext context = new StandardEvaluationContext();
        String[] paramNames = nameDiscoverer.getParameterNames(method);
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        try {
            return parser.parseExpression(expression).getValue(context, String.class);
        } catch (Exception e) {
            return expression;
        }
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add company-rag-common/src/main/java/com/company/rag/common/annotation/AuditLog.java company-rag-common/src/main/java/com/company/rag/common/aspect/AuditLogAspect.java
git commit -m "feat(security): add @AuditLog annotation and AOP aspect"
```

---

### Task 12: 创建审计日志表和 Mapper

**Files:**
- Create: `company-rag-tenant/src/main/java/com/company/rag/tenant/model/AuditLog.java`
- Create: `company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/AuditLogMapper.java`

- [ ] **Step 1: 创建 AuditLog 实体**

```java
package com.company.rag.tenant.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志
 * 记录关键操作（登录/登出、文档删除、租户管理、缓存清理等）
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long userId;
    private String actionType;     // 操作类型
    private String targetType;     // 目标类型
    private String targetId;       // 目标 ID
    private String detail;         // 操作详情（JSON）
    private String ipAddress;      // 客户端 IP
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
```

- [ ] **Step 2: 创建 AuditLogMapper**

```java
package com.company.rag.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.tenant.model.AuditLog;

public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
```

- [ ] **Step 3: 在 TenantServiceImpl 中实现 recordAuditLog 方法**

```java
// 注入 AuditLogMapper
private final AuditLogMapper auditLogMapper;

@Override
public void recordAuditLog(String actionType, String targetType, String targetId, String detail) {
    try {
        AuditLog auditLog = new AuditLog();
        auditLog.setTenantId(String.valueOf(TenantContext.getTenantId()));
        auditLog.setUserId(TenantContext.getUserId());
        auditLog.setActionType(actionType);
        auditLog.setTargetType(targetType);
        auditLog.setTargetId(targetId);
        auditLog.setDetail(detail);
        // IP 地址从 RequestContextHolder 获取
        try {
            var request = ((jakarta.servlet.http.HttpServletRequest) 
                org.springframework.web.context.request.RequestContextHolder
                    .currentRequestAttributes()
                    .resolveReference(org.springframework.web.context.request.RequestAttributes.REFERENCE_REQUEST));
            auditLog.setIpAddress(request.getRemoteAddr());
        } catch (Exception e) {
            // 获取 IP 失败不影响记录
        }
        auditLogMapper.insert(auditLog);
        log.debug("审计日志已记录: action={}, targetType={}, targetId={}",
                actionType, targetType, targetId);
    } catch (Exception e) {
        log.warn("审计日志写入失败: {}", e.getMessage());
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: 创建审计日志表的 DDL SQL 文件（供初始化使用）**

File: `company-rag-tenant/src/main/resources/sql/audit_log_ddl.sql`

```sql
-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(32)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    action_type VARCHAR(32)  NOT NULL,
    target_type VARCHAR(32),
    target_id   VARCHAR(64),
    detail      TEXT,
    ip_address  VARCHAR(45),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_tenant_action ON audit_log(tenant_id, action_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_log_created_at   ON audit_log(created_at DESC);

-- 启用行级安全策略
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;

-- 为每个租户 Schema 创建 RLS 策略（在 createTenantSchema 方法中补充）
-- DROP POLICY IF EXISTS tenant_isolation_audit_log ON %s.audit_log;
-- CREATE POLICY tenant_isolation_audit_log ON %s.audit_log
--     USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
```

同时，在 `TenantServiceImpl.createTenantSchema()` 方法中，需要在创建表的 SQL 中添加 `audit_log` 表的创建语句和 RLS 策略。

- [ ] **Step 5: Commit**

```bash
git add company-rag-tenant/src/main/java/com/company/rag/tenant/model/AuditLog.java company-rag-tenant/src/main/java/com/company/rag/tenant/mapper/AuditLogMapper.java company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java company-rag-tenant/src/main/resources/sql/audit_log_ddl.sql
git commit -m "feat(security): add AuditLog entity, mapper, and service implementation"
```

---

### Task 13: 在关键操作上添加 @AuditLog 注解

**Files:**
- Modify: `company-rag-web/src/main/java/com/company/rag/web/controller/DocumentController.java`
- Modify: `company-rag-web/src/main/java/com/company/rag/web/controller/TenantController.java`
- Modify: `company-rag-web/src/main/java/com/company/rag/web/controller/CacheManageController.java`

- [ ] **Step 1: 查看各 Controller 中需要添加 @AuditLog 的方法**

在 `DocumentController` 的删除方法上添加：
```java
@AuditLog(action = "DOCUMENT_DELETE", targetType = "document", targetId = "#id")
```

在 `TenantController` 的创建和删除方法上添加：
```java
@AuditLog(action = "TENANT_CREATE", targetType = "tenant", targetId = "#request.tenantCode")
@AuditLog(action = "TENANT_DELETE", targetType = "tenant", targetId = "#id")
```

在 `CacheManageController` 的清理方法上添加：
```java
@AuditLog(action = "CACHE_CLEAR", targetType = "cache")
```

（具体代码因 Controller 文件内容不同，根据实际方法签名调整 SpEL 表达式）

- [ ] **Step 2: Commit**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/controller/DocumentController.java company-rag-web/src/main/java/com/company/rag/web/controller/TenantController.java company-rag-web/src/main/java/com/company/rag/web/controller/CacheManageController.java
git commit -m "feat(security): add @AuditLog annotations to key operations"
```

---

### Task 14: 移除 ChatController 中的硬编码 userId

**Files:**
- Modify: `company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java`

- [ ] **Step 1: 修改 ChatController，从 SecurityContext 获取 userId**

将：
```java
// 设置默认 userId
if (request.getUserId() == null) {
    request.setUserId(1L);
}
```

改为：
```java
// 从 SecurityContext 获取当前用户 ID
if (request.getUserId() == null) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof SecurityUser securityUser) {
        request.setUserId(securityUser.getUserId());
    }
}
```

添加 import：
```java
import com.company.rag.common.security.SecurityUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
```

- [ ] **Step 2: 编译验证**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java
git commit -m "fix(security): remove hardcoded userId, get from SecurityContext"
```

---

### Task 15: 更新 .env 配置，添加 JWT_SECRET

**Files:**
- Modify: `.env`

- [ ] **Step 1: 在 .env 中添加 JWT 密钥配置**

```bash
# JWT 配置
JWT_SECRET=dGhpcyBpcyBhIHNlY3VyZSBKV1Qgc2VjcmV0IGtleSBmb3IgQ29tcGFueVJhZyBwcm9qZWN0IGluIHByb2R1Y3Rpb24=
```

> 注意：上述密钥为示例，生产环境应使用 `openssl rand -base64 64` 生成真正的随机密钥

- [ ] **Step 2: 在 application.yml 中添加 JWT 配置**

```yaml
# JWT
jwt:
  secret: ${JWT_SECRET}
  access-token-expiration: 7200000   # 2 小时
  refresh-token-expiration: 604800000  # 7 天
```

- [ ] **Step 3: Commit**

```bash
git add .env company-rag-bootstrap/src/main/resources/application.yml
git commit -m "chore: add JWT_SECRET environment variable configuration"
```

---

### Task 16: 完整编译验证

- [ ] **Step 1: 全量编译**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: 运行所有单元测试（排除集成测试）**

Run: `mvn test -q -DexcludedGroups=integration-test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: Tests pass

- [ ] **Step 3: 最终提交**

```bash
git add -A
git commit -m "feat(security): complete phase 1 security hardening"
```

---

## 启用 @PreAuthorize 的 Controller 角色映射

实施完成后，在各 Controller 方法上添加 `@PreAuthorize` 注解启用角色控制：

| Controller | 方法 | 注解 |
|-----------|------|------|
| TenantController | create/delete | `@PreAuthorize("hasRole('admin')")` |
| TenantController | list/getById | `@PreAuthorize("hasAnyRole('admin','user','viewer')")` |
| DocumentController | delete | `@PreAuthorize("hasRole('admin')")` |
| DocumentController | upload | `@PreAuthorize("hasAnyRole('admin','user')")` |
| DocumentController | list | `@PreAuthorize("hasAnyRole('admin','user','viewer')")` |
| CacheManageController | clear | `@PreAuthorize("hasRole('admin')")` |
| SessionController | list/delete | `@PreAuthorize("hasAnyRole('admin','user')")` |

此步骤可以分散在各个 Controller 修改任务中完成，也可以在最后统一添加。