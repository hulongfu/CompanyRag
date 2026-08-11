# 修复说明：移除每租户创建 admin 账号的问题

## 问题描述

**原问题：** `TenantServiceImpl.createDefaultAdminUser` 在 `createTenantWithSchema` 中被调用，导致：
- 每创建一个租户，就创建一个 `username=admin`、`password=硬编码 admin123` 的用户
- N 个租户 = N 个同名弱口令账号
- 安全风险：代码泄露 = 所有租户 admin 密码泄露

## 修复内容

### 1. 后端修改

**文件：** `company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`

**改动：**
- ✅ 删除 `createTenantWithSchema` 方法中调用 `createDefaultAdminUser(tenant)` 的代码（第 256-257 行）
- ✅ 删除 `createDefaultAdminUser` 方法（第 343-366 行）
- ✅ 修改日志输出，移除"默认管理员用户已创建"的描述

**修复后行为：**
- 创建租户时**不再创建任何用户**
- admin 账号通过初始化脚本唯一创建

### 2. 前端修改

**文件：** `company-rag-web/src/main/resources/templates/index.html`

**改动：**
- ✅ 创建用户表单（第 300-310 行）：角色选择器移除"管理员 (admin)"选项
- ✅ 编辑用户表单（第 506-515 行）：角色选择器移除"管理员 (admin)"选项

**修复后行为：**
- 创建用户时只能选择"普通用户 (user)"或"访客 (viewer)"
- 编辑用户时只能选择"普通用户 (user)"或"访客 (viewer)"

### 3. 系统初始化脚本

**文件：** `sql/migrations/V3__init_platform_admin.sql`（新建）

**内容：**
- 创建唯一的平台超级管理员账号
- 用户名：`admin`
- 密码：`admin123`（BCrypt 加密）
- 特性：幂等执行（如果 admin 已存在则跳过）

**执行方式：**
```bash
# 首次部署时执行
psql -U postgres -d company_rag -f sql/migrations/V3__init_platform_admin.sql
```

## 修复后的架构

### 角色权限设计

| 角色 | 说明 | 数量 | 创建方式 |
|------|------|------|----------|
| **admin** | 平台超级管理员，管理所有租户和用户 | 1 个 | 初始化脚本创建 |
| **user** | 普通用户，可以创建/编辑文档、对话 | 多个 | admin 通过用户管理界面创建 |
| **viewer** | 访客，只能查看 | 多个 | admin 通过用户管理界面创建 |

### 用户 - 租户关系

- **admin**：关联所有租户（手动通过用户管理界面关联）
- **user/viewer**：创建时选择关联一个或多个租户

## 部署步骤

### 首次部署

1. **执行数据库初始化脚本**
   ```bash
   psql -U postgres -d company_rag -f sql/migrations/V3__init_platform_admin.sql
   ```

2. **验证 admin 账号创建成功**
   ```sql
   SELECT id, username, display_name, role FROM sys_user WHERE username = 'admin';
   ```

3. **使用 admin/admin123 登录系统**

4. **立即修改 admin 密码**（建议）

5. **通过用户管理界面创建其他用户**
   - 角色只能选择 user 或 viewer
   - 为用户选择关联的租户

### 已部署系统升级

1. **备份现有数据**
   ```bash
   pg_dump -U postgres -d company_rag > backup_$(date +%Y%m%d).sql
   ```

2. **执行初始化脚本**（幂等，不会重复创建）
   ```bash
   psql -U postgres -d company_rag -f sql/migrations/V3__init_platform_admin.sql
   ```

3. **清理已存在的租户 admin 账号**（可选）
   ```sql
   -- 查找所有 admin 账号
   SELECT id, username, display_name, create_time FROM sys_user WHERE username = 'admin';
   
   -- 如果存在多个 admin，保留最早创建的那个，删除其他的
   -- 注意：删除前确认这些 admin 没有关联重要数据
   ```

4. **重启应用**

## 验证清单

- [ ] 创建新租户时，不再创建 admin 用户
- [ ] 用户管理界面创建用户时，角色选择只有 user 和 viewer
- [ ] 用户管理界面编辑用户时，角色选择只有 user 和 viewer
- [ ] 使用 admin/admin123 可以登录系统
- [ ] admin 可以创建租户
- [ ] admin 可以创建用户（user/viewer 角色）
- [ ] admin 可以为用户关联租户

## 安全建议

1. **首次登录后立即修改 admin 密码**
2. **定期审计用户权限**
3. **为不同租户创建独立的 user 账号，不要共享**
4. **启用密码复杂度策略**（如果业务需要）
5. **考虑实现密码过期策略**

## 回滚方案

如果修复后出现问题，可以通过以下方式回滚：

1. **恢复代码**
   ```bash
   git checkout HEAD~1 -- company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java
   git checkout HEAD~1 -- company-rag-web/src/main/resources/templates/index.html
   ```

2. **恢复数据库**
   ```bash
   psql -U postgres -d company_rag < backup_YYYYMMDD.sql
   ```

## 相关文件

- 后端实现：`company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`
- 前端页面：`company-rag-web/src/main/resources/templates/index.html`
- 初始化脚本：`sql/migrations/V3__init_platform_admin.sql`

## 修改日期

2026-08-11
