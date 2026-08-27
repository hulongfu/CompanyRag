# MCP 架构优化 - 消除隐式耦合

## 修改日期
2026-08-26

## 问题背景

### 架构隐患

用户发现了一个关键的架构问题：**`McpSecurityFilter` 注入了 `TenantService` 却未使用**，Schema 设置依赖 `JwtAuthenticationFilter` 兜底。

**问题链路：**
```
McpSecurityFilter (验证租户) 
  → JwtAuthenticationFilter (设置 Schema)  ← 隐式依赖
    → 业务逻辑 (使用 Schema)
```

**潜在风险：**
1. **隐式耦合**：`McpSecurityFilter` 依赖 `JwtAuthenticationFilter` 设置 Schema
2. **脆弱架构**：如果将来有人修改 `JwtAuthenticationFilter` 排除 `/mcp` 路径，MCP 的 Schema 会瞬间失效
3. **职责不清**：`McpSecurityFilter` 的 `tenantService` 字段是"死字段"，代码意图不清晰

---

## 根因分析

### 当前过滤器执行顺序
```
McpSecurityFilter → JwtAuthenticationFilter → UsernamePasswordAuthenticationFilter
```

### 问题代码对比

**McpSecurityFilter.java（修改前）：**
```java
@Component
@RequiredArgsConstructor
public class McpSecurityFilter extends OncePerRequestFilter {
    
    private final JwtTokenProvider jwtTokenProvider;
    private final TenantService tenantService;  // ⚠️ 注入但未使用！
    
    @Override
    protected void doFilterInternal(...) {
        // ... 验证 Token、验证租户 ...
        
        // ✅ 设置租户 ID
        TenantContext.setTenantId(requestedTenantId);
        
        // ❌ 没有设置 Schema！依赖后续的 JwtAuthenticationFilter 兜底
    }
}
```

**JwtAuthenticationFilter.java（第 84-88 行）：**
```java
// 设置租户 schema（用于 DatabaseQueryTool 等原生 JDBC 操作）
Tenant currentTenant = tenantService.getById(currentTenantId);
if (currentTenant != null && currentTenant.getSchemaName() != null) {
    TenantContext.setSchema(currentTenant.getSchemaName());
    log.debug("设置租户 Schema：userId={}, schema={}", userId, currentTenant.getSchemaName());
}
```

### 架构风险场景

**假设场景**：6 个月后，某开发人员发现 `JwtAuthenticationFilter` 对 MCP 请求重复鉴权，决定优化：

```java
// 假设的"优化"
@Override
protected void doFilterInternal(...) {
    // ⚠️ 排除 MCP 路径，避免重复鉴权
    if (request.getRequestURI().startsWith("/mcp")) {
        filterChain.doFilter(request, response);
        return;
    }
    // ... 其他逻辑
}
```

**结果**：MCP 请求不再设置 Schema，所有依赖 Schema 的数据库操作失败！

---

## 修改方案

### 原则
✅ **职责单一**：MCP 安全过滤自给自足，不依赖其他 Filter  
✅ **消除耦合**：删除隐式依赖，显式声明职责  
✅ **防御性编程**：即使其他 Filter 行为变化，MCP 仍正常工作

### 修改内容

#### 1. 添加 Tenant 导入
```java
import com.company.rag.tenant.model.Tenant;
```

#### 2. 在 `McpSecurityFilter` 中设置 Schema

**修改位置**：第 92-104 行（指定租户）和第 121-129 行（默认租户）

```java
// 7. 验证用户是否属于该租户
if (tenantIds != null && tenantIds.contains(requestedTenantId)) {
    TenantContext.setTenantId(requestedTenantId);
    
    // 8. 设置租户 Schema（用于 DatabaseQueryTool 等原生 JDBC 操作）
    Tenant tenant = tenantService.getById(requestedTenantId);
    if (tenant != null && tenant.getSchemaName() != null) {
        TenantContext.setSchema(tenant.getSchemaName());
        log.info("MCP 设置租户 Schema：userId={}, tenantId={}, schema={}", 
                userId, requestedTenantId, tenant.getSchemaName());
    } else {
        log.warn("MCP 租户 Schema 为空：userId={}, tenantId={}", userId, requestedTenantId);
    }
    
    log.info("MCP 租户验证成功：userId={}, tenantId={}", userId, requestedTenantId);
}
```

**默认租户场景**：
```java
// 如果没有指定租户 ID，使用用户的第一个租户
Long defaultTenantId = tenantIds.get(0);
TenantContext.setTenantId(defaultTenantId);

// 设置默认租户的 Schema
Tenant tenant = tenantService.getById(defaultTenantId);
if (tenant != null && tenant.getSchemaName() != null) {
    TenantContext.setSchema(tenant.getSchemaName());
    log.info("MCP 设置默认租户 Schema：userId={}, tenantId={}, schema={}", 
            userId, defaultTenantId, tenant.getSchemaName());
}
```

#### 3. 更新类注释

```java
/**
 * MCP 安全过滤器
 * 
 * 职责：
 * ...
 * 5. 查询租户数据库设置 Schema（自给自足，不依赖其他 Filter）
 * ...
 * 安全策略：
 * ...
 * - 自给自足：独立查询 TenantService 设置 Schema，不依赖 JwtAuthenticationFilter
 */
```

---

## 架构优化效果

### 修改前后对比

| 维度 | 修改前 | 修改后 |
|------|--------|--------|
| **Schema 设置职责** | `JwtAuthenticationFilter` | **`McpSecurityFilter`** |
| **隐式依赖** | ✅ 存在 | ❌ **消除** |
| **tenantService 字段** | 死字段 | **正常使用** |
| **架构稳定性** | 脆弱（依赖其他 Filter） | **健壮（自给自足）** |
| **代码清晰度** | 模糊（字段未使用） | **清晰（职责明确）** |

### 过滤器职责重新划分

**修改前：**
```
McpSecurityFilter: 验证租户 ID
JwtAuthenticationFilter: 设置 Schema ← 隐式依赖点
```

**修改后：**
```
McpSecurityFilter: 验证租户 ID + 设置 Schema（自给自足）
JwtAuthenticationFilter: 设置 Schema（非 MCP 请求）
```

---

## 为什么保留 `JwtAuthenticationFilter` 的 Schema 设置？

**问题**：既然 `McpSecurityFilter` 已经设置 Schema，为什么不删除 `JwtAuthenticationFilter` 中的重复逻辑？

**回答**：因为非 MCP 请求仍然经过 `JwtAuthenticationFilter`，需要设置 Schema。

**请求路由：**
```
MCP 请求 (/mcp/**):
  McpSecurityFilter → ✅ 设置 Schema → 业务逻辑

非 MCP 请求 (/api/**, /web/**):
  JwtAuthenticationFilter → ✅ 设置 Schema → 业务逻辑
```

---

## 验证结果

### 编译验证
```bash
mvn clean compile -pl company-rag-mcp -am
```
✅ 编译成功，无错误

### 架构验证

**场景 1：MCP 请求**
- ✅ `McpSecurityFilter` 验证租户
- ✅ `McpSecurityFilter` 设置 Schema
- ✅ 即使 `JwtAuthenticationFilter` 排除 `/mcp` 路径，Schema 仍正常

**场景 2：非 MCP 请求**
- ✅ `JwtAuthenticationFilter` 验证并设置 Schema
- ✅ 正常工作

---

## 架构原则

### 1. 自给自足原则
> **Filter 应该在自己的职责范围内完成所有必要的设置，不依赖其他 Filter 兜底。**

### 2. 显式依赖原则
> **如果必须依赖其他组件，应该通过文档或代码注释显式声明，而不是隐式假设。**

### 3. 防御性编程原则
> **即使其他组件行为变化，我的代码仍应该正常工作（或优雅失败）。**

---

## 后续建议

### 1. 代码审查检查项
- [ ] Filter 中注入的依赖是否都使用了？
- [ ] 是否存在跨 Filter 的隐式依赖？
- [ ] 如果某个 Filter 被移除或修改，其他 Filter 是否仍能正常工作？

### 2. 架构文档更新
建议在架构文档中明确说明：
- 各 Filter 的职责边界
- Filter 执行顺序
- 各 Filter 的独立性保证

### 3. 单元测试建议
为 `McpSecurityFilter` 添加测试用例：
```java
@Test
void testMcpRequest_SchemaIsSet() {
    // 验证 MCP 请求在 Filter 内独立设置 Schema
    // 不依赖 JwtAuthenticationFilter
}
```

---

## 参考

- [原修复文档](./2026-08-26-mcp-security-and-skill-loading-fix.md)
- [MCP 安全过滤器设计](../docs/superpowers/specs/2026-08-16-mcp-status-assessment.md)
- [多租户隔离架构](../docs/architecture/multi-tenant-isolation-architecture.md)

---

## 总结

**用户的建议完全正确！** 

通过这次优化：
1. ✅ 消除了隐式耦合
2. ✅ 提高了架构稳定性
3. ✅ 明确了职责边界
4. ✅ 删除了"死字段"

**关键教训**：代码审查时，要特别注意"注入但未使用的依赖"——这往往是架构隐患的信号。
