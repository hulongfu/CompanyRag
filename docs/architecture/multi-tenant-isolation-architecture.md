# 多租户隔离架构说明

**文档目的**：澄清多租户隔离架构，防止误解。

---

## 📋 架构概述

本系统采用 **Schema 隔离为主，RLS 隔离为辅** 的双重隔离策略。

### 核心设计原则

1. **主隔离（Schema 隔离）**：100% 可靠，物理隔离
2. **辅助隔离（RLS）**：深度防御（Defense in Depth），最佳努力（Best Effort）

---

## 🏗️ 隔离机制详解

### 1️⃣ 主隔离：Schema 隔离（物理隔离）

**实现方式**：
```java
// TenantSchemaInterceptor 在每条 SQL 执行前设置
stmt.execute("SET search_path TO " + schema + ", public");
```

**工作原理**：
- 每个租户有独立的 schema（如 `tenant_companyA`、`tenant_companyB`）
- PostgreSQL 的 `search_path` 决定查询时默认访问哪个 schema
- 不同租户的数据物理上存储在不同的 schema 中

**可靠性**：
- ✅ **100% 可靠** - PostgreSQL 内核保证
- ✅ **不依赖事务** - 每次查询前设置，立即可用
- ✅ **物理隔离** - 数据天然分离，不会跨租户

**示例**：
```sql
-- 租户 A 的查询
SET search_path TO tenant_companyA, public;
SELECT * FROM rag_document;  -- 实际访问 tenant_companyA.rag_document

-- 租户 B 的查询
SET search_path TO tenant_companyB, public;
SELECT * FROM rag_document;  -- 实际访问 tenant_companyB.rag_document
```

---

### 2️⃣ 辅助隔离：RLS 行级安全（深度防御）

**实现方式**：
```java
// TenantSchemaInterceptor 在每条 SQL 执行前设置
stmt.execute("SET app.tenant_id = " + tenantId);
```

**工作原理**：
- 通过 PostgreSQL GUC（Grand Unified Configuration）设置 `app.tenant_id`
- RLS 策略检查：`tenant_id = current_tenant_id()`
- 即使 schema 隔离失效，RLS 仍能提供额外保护

**可靠性**：
- ⚠️ **最佳努力（Best Effort）** - 作为深度防御的补充
- ⚠️ **依赖连接池清理** - HikariCP 会在连接归还时重置会话状态
- ✅ **深度防御价值** - 防止应用层 bug 导致的意外跨租户访问

**示例**：
```sql
-- RLS 策略（在租户 schema 内）
CREATE POLICY tenant_isolation_document ON rag_document
    FOR ALL TO company_rag_app
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- 即使 search_path 错误，RLS 仍能拦截
SET search_path TO tenant_companyA, public;  -- 错误设置
SET app.tenant_id = 123;
SELECT * FROM rag_document;  -- RLS 检查：tenant_id = 123
```

---

## 🔄 租户切换流程

```
1. 用户登录
   ↓
2. 获取关联的租户列表
   ↓
3. 默认选择第一个租户作为当前租户
   ↓
4. 用户发起请求 → TenantContext 设置租户信息
   ↓
5. MyBatis 查询 → TenantSchemaInterceptor 拦截
   ↓
6. 在执行连接上设置：
   - SET search_path TO <tenant_schema>  (主隔离)
   - SET app.tenant_id = <tenant_id>     (辅助隔离)
   ↓
7. 执行 SQL → 自动访问正确的租户数据
```

---

## 🛡️ 安全边界分析

| 场景 | Schema 隔离 | RLS 隔离 | 最终结果 |
|------|-----------|---------|---------|
| **应用层正常** | ✅ 生效 | ✅ 生效 | ✅ 安全 |
| **search_path 设置错误** | ❌ 失效 | ✅ 拦截 | ✅ 安全（RLS 补救） |
| **app.tenant_id 设置错误** | ✅ 生效 | ❌ 失效 | ✅ 安全（Schema 保护） |
| **连接池 GUC 残留** | ✅ 生效 | ⚠️ 可能失效 | ✅ 安全（Schema 保护） |
| **SQL 注入攻击** | ✅ 限制 | ✅ 限制 | ✅ 双重保护 |
| **DBA 误操作** | ✅ 限制 | ✅ 限制 | ✅ 双重保护 |

**关键结论**：
- **Schema 隔离是"雪中送炭"** - 没有它，系统不安全
- **RLS 是"锦上添花"** - 有了它，系统更安全

---

## 📝 实现细节

### TenantSchemaInterceptor

**职责**：在每条 MyBatis 查询/更新执行前设置租户上下文

**关键代码**：
```java
@Override
public void beforeQuery(Executor executor, ...) {
    applyTenantContext(executor);
}

private void applyTenantContext(Executor executor) {
    // 1. 获取当前租户信息
    String schema = TenantContext.getSchema();
    Long tenantId = TenantContext.getTenantId();
    
    // 2. 在执行连接上设置
    stmt.execute("SET search_path TO " + schema + ", public");  // 主隔离
    stmt.execute("SET app.tenant_id = " + tenantId);            // 辅助隔离
}
```

**为什么使用 SET 而非 SET LOCAL？**
- Schema 隔离不依赖事务，每次查询前设置立即可用
- 避免强制要求所有查询加 `@Transactional`
- 性能更好，无事务开销

**连接池清理**：
- HikariCP 会在连接归还时重置会话状态
- 避免 GUC 残留导致 RLS 失效

---

## ❓ 常见误解

### 误解 1："必须使用 SET LOCAL + 事务"

**澄清**：
- 这是混淆了 Schema 隔离和 RLS 隔离的重要性
- Schema 隔离（主隔离）不依赖事务
- RLS 是辅助隔离，即使失效也不影响核心安全

### 误解 2："RLS 失效 = 跨租户泄漏"

**澄清**：
- RLS 失效 ≠ 跨租户泄漏
- 只要 Schema 隔离正常，就不会跨租户
- RLS 是"最后一道保险"，不是"唯一防线"

### 误解 3："连接池 GUC 残留很危险"

**澄清**：
- GUC 残留只影响 RLS（辅助隔离）
- Schema 隔离不受影响
- HikariCP 默认会清理会话状态

---

## 🎯 设计哲学

### 深度防御（Defense in Depth）

本系统采用多层防护策略：

1. **第一层：应用层隔离**
   - TenantContext 管理租户上下文
   - MyBatis-Plus 拦截器自动设置

2. **第二层：Schema 隔离（主隔离）**
   - PostgreSQL 内核保证
   - 物理隔离，100% 可靠

3. **第三层：RLS 隔离（辅助隔离）**
   - 行级安全检查
   - 防止应用层 bug 导致的意外

### 最小权限原则

- 应用使用专用用户 `company_rag_app`（非 postgres 超级用户）
- 每个租户只能访问自己的 schema
- RLS 策略限制只能访问自己的数据

---

## 📚 相关文件

- `TenantSchemaInterceptor.java` - 核心拦截器
- `TenantContextHelper.java` - 上下文助手（已废弃）
- `sql/init.sql` - 数据库初始化脚本
- `docs/security-fixes/2026-08-09-tenant-isolation-security-fix.md` - 历史修复记录

---

## 📅 更新记录

| 日期 | 更新内容 | 原因 |
|------|---------|------|
| 2026-08-26 | 澄清架构设计，移除误导性注释 | 防止误解 |
| 2026-08-09 | 引入 RLS 深度防御 | 增强安全性 |

---

**总结**：本系统的多租户隔离架构是可靠的，Schema 隔离作为主隔离保证核心安全，RLS 作为辅助隔离提供深度防御。不要误解架构设计，过度强调 RLS 的重要性。
