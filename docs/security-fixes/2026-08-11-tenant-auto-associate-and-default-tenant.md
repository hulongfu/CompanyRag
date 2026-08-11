# 修复说明：租户创建时自动关联用户 + 初始化默认租户

## 问题描述

### 问题 1：创建租户时未自动关联创建者
**现象：**
- admin 创建租户后，需要手动通过用户管理界面关联自己
- 否则 admin 无法访问新创建的租户资源

**根因：**
- `TenantServiceImpl.createTenantWithSchema` 只创建租户和 schema
- 没有建立创建者与租户的关联关系

### 问题 2：admin 账号无法首次登录
**现象：**
- 首次部署后，admin 账号创建成功但无法登录
- 登录时报错："用户没有关联任何租户"

**根因：**
- `TenantServiceImpl.loadSecurityUserByUsername:352-355` 检查用户必须关联至少一个租户
- 初始化脚本只创建 admin 账号，未创建默认租户和关联关系

## 修复内容

### 1. 后端修改

**文件：** `company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`

**改动：**
- ✅ 在 `createTenantWithSchema` 方法中添加自动关联当前登录用户的逻辑（第 256-270 行）
- ✅ 通过 `SecurityContextHolder` 获取当前登录用户
- ✅ 创建 `UserTenantRel` 记录建立用户 - 租户关联

**代码片段：**
```java
// 5. 自动关联当前登录用户（创建者）
// 通过 SecurityContextHolder 获取当前登录用户
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
if (authentication != null && authentication.getPrincipal() instanceof SecurityUser) {
    SecurityUser currentUser = (SecurityUser) authentication.getPrincipal();
    // 建立用户 - 租户关联
    UserTenantRel rel = new UserTenantRel();
    rel.setUserId(currentUser.getUserId());
    rel.setTenantId(tenant.getId());
    userTenantRelMapper.insert(rel);
    log.info("已自动关联当前用户与租户：userId={}, tenantId={}", 
        currentUser.getUserId(), tenant.getId());
} else {
    log.warn("当前用户未认证，跳过用户 - 租户关联");
}
```

**修复后行为：**
- admin 创建租户时，自动建立 admin 与该租户的关联
- 普通用户创建租户时（如果有权限），也会自动关联

### 2. SQL 初始化脚本修改

**文件：** `sql/migrations/V3__init_platform_admin.sql`

**改动：**
- ✅ 创建 admin 账号（用户名：admin，密码：admin123）
- ✅ 创建默认租户（tenant_code='tenant_default'，tenant_name='默认租户'）
- ✅ 建立 admin 与默认租户的关联关系
- ✅ 创建默认租户的 schema：`tenant_tenant_default`
- ✅ 创建 5 个业务表：rag_document、doc_chunk、vector_store、rag_session、rag_session_meta
- ✅ 创建索引（包括 HNSW 向量索引）
- ✅ 启用 RLS（行级安全）策略
- ✅ 授予 `company_rag_app` 用户权限

**修复后行为：**
- 首次部署时，admin 账号立即可用
- admin 登录后默认关联默认租户
- 默认租户包含完整的业务表和索引

## 修复后的架构

### 用户 - 租户关系

```
┌─────────────────────────────────────┐
│         平台超级管理员 (admin)       │
│         用户名：admin               │
│         密码：admin123              │
│         数量：唯一 1 个              │
└──────────────┬──────────────────────┘
               │
               │ 自动关联所有创建的租户
               ▼
┌─────────────────────────────────────┐
│  默认租户  │  租户 A  │  租户 B  │ ... │
│  (自动)   │  (创建)  │  (创建)  │     │
└─────────────────────────────────────┘
       │         │         │
       ▼         ▼         ▼
   user/viewer  user/viewer  user/viewer
   (创建时关联租户)
```

### 部署流程

**首次部署：**
1. 执行 SQL 初始化脚本 → admin 账号 + 默认租户创建成功
2. 使用 admin/admin123 登录系统 → 自动关联默认租户
3. 通过租户管理界面创建其他租户 → 自动关联创建者（admin）
4. 通过用户管理界面创建其他用户 → 手动选择关联租户

**已部署系统升级：**
1. 备份现有数据
2. 执行 SQL 初始化脚本（幂等，不会重复创建）
3. 重启应用
4. 新创建的租户会自动关联创建者

## 部署步骤

### 首次部署

1. **执行数据库初始化脚本**
   ```bash
   psql -U postgres -d company_rag -f sql/migrations/V3__init_platform_admin.sql
   ```

2. **验证初始化成功**
   ```sql
   -- 查看 admin 账号
   SELECT id, username, display_name, role FROM sys_user WHERE username = 'admin';
   
   -- 查看默认租户
   SELECT id, tenant_code, tenant_name, schema_name FROM sys_tenant WHERE tenant_code = 'tenant_default';
   
   -- 查看关联关系
   SELECT u.username, t.tenant_code, t.tenant_name
   FROM sys_user_tenant_rel rel
   JOIN sys_user u ON rel.user_id = u.id
   JOIN sys_tenant t ON rel.tenant_id = t.id
   WHERE u.username = 'admin';
   
   -- 查看默认租户的 schema
   SELECT schema_name FROM information_schema.schemata 
   WHERE schema_name = 'tenant_tenant_default';
   
   -- 查看默认租户的业务表
   SELECT table_name FROM information_schema.tables 
   WHERE table_schema = 'tenant_tenant_default';
   ```

3. **使用 admin/admin123 登录系统**

4. **立即修改 admin 密码**（建议）

5. **创建其他租户**（会自动关联 admin）

6. **创建其他用户**（user/viewer 角色，手动选择关联租户）

### 已部署系统升级

1. **备份现有数据**
   ```bash
   pg_dump -U postgres -d company_rag > backup_$(date +%Y%m%d).sql
   ```

2. **执行初始化脚本**（幂等，不会重复创建）
   ```bash
   psql -U postgres -d company_rag -f sql/migrations/V3__init_platform_admin.sql
   ```

3. **清理已存在的多个 admin 账号**（可选）
   ```sql
   -- 查找所有 admin 账号
   SELECT id, username, display_name, create_time FROM sys_user WHERE username = 'admin';
   
   -- 如果存在多个 admin，保留最早创建的那个，删除其他的
   -- 注意：删除前确认这些 admin 没有关联重要数据
   ```

4. **重启应用**

## 验证清单

- [ ] SQL 脚本执行成功，无报错
- [ ] admin 账号创建成功
- [ ] 默认租户 tenant_default 创建成功
- [ ] admin 与默认租户的关联关系创建成功
- [ ] 默认租户的 schema `tenant_tenant_default` 创建成功
- [ ] 5 个业务表创建成功（rag_document、doc_chunk、vector_store、rag_session、rag_session_meta）
- [ ] 索引创建成功（包括 HNSW 向量索引）
- [ ] RLS 策略启用成功
- [ ] 使用 admin/admin123 可以登录系统
- [ ] admin 登录后可以看到默认租户
- [ ] admin 创建新租户时，自动关联 admin
- [ ] admin 可以访问新创建租户的资源
- [ ] admin 可以创建用户（user/viewer 角色）
- [ ] admin 可以为用户关联租户

## 技术细节

### 1. 自动关联实现原理

**触发时机：** `createTenantWithSchema` 方法中，创建 schema 后

**实现方式：**
```java
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
if (authentication != null && authentication.getPrincipal() instanceof SecurityUser) {
    SecurityUser currentUser = (SecurityUser) authentication.getPrincipal();
    UserTenantRel rel = new UserTenantRel();
    rel.setUserId(currentUser.getUserId());
    rel.setTenantId(tenant.getId());
    userTenantRelMapper.insert(rel);
}
```

**优点：**
- 符合直觉：谁创建谁管理
- 无需手动关联
- 支持多用户创建租户（每个创建者都自动关联）

### 2. 默认租户 Schema 创建

**SQL 脚本创建（简化版）：**
- 创建 schema `tenant_tenant_default`
- 创建 5 个业务表（基础结构）
- 创建索引（包括 HNSW）
- 启用 RLS
- 授予权限

**Java 代码创建（完整版）：**
- `TenantServiceImpl.createTenantSchema` 方法
- 包含完整的表结构、索引、触发器、RLS 策略
- 支持全文检索（tsvector + GIN 索引）

**注意：** SQL 脚本创建的是简化版，完整的表结构由 Java 代码在运行时创建。

### 3. 幂等性保证

**admin 账号创建：**
```sql
INSERT INTO sys_user (...) 
SELECT ... WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');
```

**默认租户创建：**
```sql
INSERT INTO sys_tenant (...) 
SELECT ... WHERE NOT EXISTS (SELECT 1 FROM sys_tenant WHERE tenant_code = 'tenant_default');
```

**关联关系创建：**
```sql
INSERT INTO sys_user_tenant_rel (...) 
SELECT ... WHERE NOT EXISTS (SELECT 1 FROM sys_user_tenant_rel WHERE ...);
```

**Schema 创建：**
```sql
CREATE SCHEMA IF NOT EXISTS tenant_tenant_default;
```

**表创建：**
```sql
CREATE TABLE IF NOT EXISTS tenant_tenant_default.xxx (...);
```

**索引创建：**
```sql
CREATE INDEX IF NOT EXISTS idx_xxx ...;
```

## 安全建议

1. ⚠️ **首次登录后立即修改 admin 密码**
2. 📋 **定期审计用户权限**
3. 👥 **为不同租户创建独立的 user 账号，不要共享**
4. 🔒 **考虑启用密码复杂度策略**
5. 🔐 **考虑实现密码过期策略**
6. 📊 **启用审计日志（待实现）**

## 回滚方案

如果修复后出现问题，可以通过以下方式回滚：

1. **恢复代码**
   ```bash
   git checkout HEAD~1 -- company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java
   ```

2. **恢复数据库**
   ```bash
   psql -U postgres -d company_rag < backup_YYYYMMDD.sql
   ```

## 相关文件

- 后端实现：`company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`
- SQL 初始化脚本：`sql/migrations/V3__init_platform_admin.sql`
- 修复说明文档：`docs/security-fixes/2026-08-11-remove-per-tenant-admin-fix.md`

## 修改日期

2026-08-11

## 附录：默认租户业务表列表

| 表名 | 说明 | 索引 |
|------|------|------|
| `rag_document` | 文档元数据 | idx_tenant_default_doc_tenant, idx_tenant_default_document_title_trgm |
| `doc_chunk` | 文档切片 | idx_tenant_default_chunk_document, idx_tenant_default_chunk_content_trgm |
| `vector_store` | 向量存储 | idx_tenant_default_vector_store_embedding (HNSW) |
| `rag_session` | 会话记录 | idx_tenant_default_session_tenant |
| `rag_session_meta` | 会话元数据 | - |

## 附录：RLS 策略列表

| 表名 | 策略名 | 策略内容 |
|------|--------|----------|
| `rag_document` | tenant_isolation_document | tenant_id = current_tenant_id() |
| `doc_chunk` | tenant_isolation_chunk | tenant_id = current_tenant_id() |
| `rag_session` | tenant_isolation_session | tenant_id = current_tenant_id() |
| `rag_session_meta` | tenant_isolation_session_meta | tenant_id = current_tenant_id() |

**注意：** `vector_store` 表不使用 RLS，仅通过 Schema 隔离（见 `TenantServiceImpl:88-92` 注释）。
