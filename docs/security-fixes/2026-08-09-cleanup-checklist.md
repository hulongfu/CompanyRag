# 多租户隔离安全修复 - 清理清单

**修复日期**: 2026-08-09  
**状态**: ✅ 核心安全问题已闭环，剩余卫生清理项

---

## ✅ 已完成的核心修复

| 修复项 | 文件 | 状态 |
|--------|------|------|
| 创建专用用户 `company_rag_app` | `sql/init.sql` | ✅ |
| 移除 RLS 策略中的 `OR current_user='postgres'` 后门 | `init.sql`, `TenantServiceImpl.java` | ✅ |
| 增加 `WITH CHECK` + `FORCE ROW LEVEL SECURITY` | `init.sql`, `TenantServiceImpl.java`, `001` | ✅ |
| 撤销 `vector_store` 的错误 RLS（恢复仅 Schema 隔离） | `TenantServiceImpl.java`, `001` | ✅ |
| 修复 GUC 设置（在 MyBatis 连接上 SET） | `TenantSchemaInterceptor.java` | ✅ |
| 废弃 `TenantContextHelper` 的 SET 操作 | `TenantContextHelper.java` | ✅ |
| 清理存量租户的旧回归 | `001-fix-tenant-isolation-security.sql` | ✅ |
| 补充系统表授权到 `init.sql` | `sql/init.sql` | ✅ |
| 补充 `vector_store` Schema 隔离测试 | `RlsIsolationTest.java` | ✅ |

---

## 🟢 卫生清理项（不影响隔离正确性）

### 1. 明文密码管理

**现状**:
- `init.sql:21`: `CREATE USER company_rag_app WITH PASSWORD 'company_rag_app_password_change_me'`
- `application.yml:22`: `password: ${POSTGRES_PASSWORD:company_rag_app_password_change_me}`
- `RlsIsolationTest.java:36`: `TEST_PASSWORD = "company_rag_app_password_change_me"`
- `001:19`: 同 `init.sql`

**建议**:
- ✅ **已正确使用环境变量**：`application.yml` 已使用 `${POSTGRES_PASSWORD:...}`
- 🟠 **init.sql 需要保留默认值**：作为初始化脚本，需要默认密码用于首次部署
- 🟠 **测试代码需要硬编码**：单元测试需要确定的测试凭据
- ✅ **文档应说明**：生产环境必须修改默认密码

**结论**: 当前设计合理，无需修改。需要在文档中强调生产环境必须修改默认密码。

---

### 2. 历史文档更新（6 个文件包含旧后门写法）

**文件列表**:
1. `docs/superpowers/specs/2026-01-18-session-history-design.md`
2. `docs/superpowers/plans/2026-01-18-session-history-implementation.md`
3. `docs/fix-rag-session-meta-and-delete-tenant.md`
4. `docs/security-fixes/2026-08-09-rls-connection-pool-fix.md`
5. `docs/security-fixes/2026-08-09-tenant-isolation-security-fix.md`
6. `docs/superpowers/plans/2026-08-02-phase1-security-implementation.md`

**问题**: 这些文档记录了旧的错误实现（`OR current_user='postgres'`），可能误导读者。

**建议**:
- ✅ **保留历史**：这些文档记录了修复过程，有历史价值
- 🟠 **增加免责声明**：在文档开头增加"⚠️ 已过时"提示
- ✅ **链接到最新文档**：引导读者查看最新实现

**操作**: 为每个文档增加顶部警告框，链接到最新实现。

---

### 3. session-history-tables.sql 建表无 schema 限定

**现状**:
```sql
-- session-history-tables.sql
CREATE TABLE rag_session (...)  -- 无 schema 限定
```

**问题**: 单独执行会落到 `public` schema，但实际应该落到租户 schema。

**分析**:
- ✅ **该文件是历史遗留**：会话表现在通过 `TenantServiceImpl.createTenantSchema()` 动态创建
- ✅ **不影响运行**：实际部署不执行此脚本
- 🟠 **可能误导**：新开发者可能误用

**建议**: 在文件顶部增加"⚠️ 已废弃"注释，说明现在由 `TenantServiceImpl` 动态创建。

---

### 4. README.md 等文档同步

**需要检查的点**:
- ✅ 多租户架构描述是否准确
- ✅ 是否包含过时的安全实现细节
- ✅ 快速开始指南是否正确

**当前状态**: README.md 描述准确，无需修改。

---

## 📋 清理行动计划

### P2（建议项，不影响安全）

1. **为历史文档增加"⚠️ 已过时"警告**
   - 文件：6 个 docs/ 文件
   - 操作：在文件顶部增加警告框，链接到最新实现

2. **为 session-history-tables.sql 增加"⚠️ 已废弃"注释**
   - 文件：`sql/session-history-tables.sql`
   - 操作：说明现在由 `TenantServiceImpl` 动态创建

3. **更新安全修复文档的结论**
   - 文件：`docs/security-fixes/2026-08-09-tenant-isolation-security-fix.md`
   - 操作：增加"✅ 已闭环"状态说明

---

## 🎯 最终状态

**核心安全**: ✅ 完全闭环  
**文档卫生**: 🟡 建议清理（不影响运行）  
**配置管理**: ✅ 已正确使用环境变量  

---

**验证协议**:
1. 新建租户不报错 → `TenantServiceImpl` 不再包含 `vector_store` 的 RLS
2. 旧租户可正常访问 → `001` 脚本清理了旧回归
3. docker-compose 一键启动 → `init.sql` 包含系统表授权
4. 测试覆盖 → `RlsIsolationTest` 包含 RLS + Schema 隔离测试
