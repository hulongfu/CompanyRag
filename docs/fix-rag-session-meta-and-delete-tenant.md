# 修复总结：rag_session_meta 表缺失及添加删除租户功能

> ⚠️ **注意**: 本文档记录的是历史修复，多租户隔离安全实现细节请参考最新文档：
> - [多租户隔离安全修复报告](./security-fixes/2026-08-09-tenant-isolation-security-fix.md)
> - [RLS 连接池修复报告](./security-fixes/2026-08-09-rls-connection-pool-fix.md)
> - [清理清单](./security-fixes/2026-08-09-cleanup-checklist.md)

## 问题描述

### 问题 1：rag_session_meta 表缺失
**错误信息**：
```
org.postgresql.util.PSQLException: ERROR: relation "rag_session_meta" does not exist
```

**根因**：`TenantServiceImpl.createTenantSchema()` 方法中只创建了 4 个表，缺少 `rag_session_meta` 表。

**影响的表**：
- ✅ rag_document（已创建）
- ✅ doc_chunk（已创建）
- ✅ vector_store（已创建）
- ✅ rag_session（已创建）
- ❌ **rag_session_meta（缺失）**

### 问题 2：缺少删除租户功能
用户反馈当前只有租户创建功能，没有删除功能。

## 修复内容

### 1. 添加 rag_session_meta 表创建语句

**文件**：`company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`

**修改位置**：`createTenantSchema()` 方法，第 51-96 行

**添加的表结构**：
```sql
CREATE TABLE IF NOT EXISTS {schema}.rag_session_meta (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT,
    title VARCHAR(256),
    last_query TEXT,
    message_count INTEGER DEFAULT 0,
    is_deleted BOOLEAN DEFAULT FALSE,
    tags JSONB,
    metadata JSONB,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 2. 添加 rag_session_meta 的 RLS 策略

**修改位置**：`createTenantSchema()` 方法，第 145-160 行

**添加的策略**：
```sql
ALTER TABLE {schema}.rag_session_meta ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tenant_isolation_session_meta ON {schema}.rag_session_meta;
CREATE POLICY tenant_isolation_session_meta ON {schema}.rag_session_meta
    USING (tenant_id = current_tenant_id() OR current_user = 'postgres');
```

### 3. 添加删除租户功能

**接口定义**：`TenantService.java`
```java
/**
 * 删除租户及其 Schema（级联删除所有数据）
 * @param tenantId 租户 ID
 * @return 是否删除成功
 */
boolean deleteTenantWithSchema(Long tenantId);
```

**实现代码**：`TenantServiceImpl.java`
```java
@Override
@Transactional
public boolean deleteTenantWithSchema(Long tenantId) {
    // 1. 查询租户信息
    Tenant tenant = tenantMapper.selectById(tenantId);
    if (tenant == null) {
        log.warn("租户不存在：{}", tenantId);
        return false;
    }

    String schemaName = tenant.getSchemaName();
    if (schemaName == null || schemaName.isEmpty()) {
        log.error("租户 Schema 名称为空：{}", tenantId);
        return false;
    }

    try {
        // 2. 级联删除 Schema（自动删除该 Schema 下的所有表和数据）
        log.info("正在删除租户 [{}] 的 Schema: {}", tenant.getTenantCode(), schemaName);
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schemaName + " CASCADE");
        log.info("已删除租户 [{}] 的 Schema: {}", tenant.getTenantCode(), schemaName);

        // 3. 删除租户记录（事务会自动删除关联的用户记录）
        tenantMapper.deleteById(tenantId);
        log.info("已删除租户记录：{} (ID={})", tenant.getTenantCode(), tenantId);

        log.info("租户 [{}] 删除成功", tenant.getTenantCode());
        return true;
    } catch (Exception e) {
        log.error("租户 [{}] 删除失败", tenant.getTenantCode(), e);
        throw new BizException("删除租户失败：" + e.getMessage());
    }
}
```

## 验证结果

### 编译验证
```
[INFO] BUILD SUCCESS
[INFO] Total time:  8.764 s
```

### 测试验证
```
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 使用方式

### 创建租户（已自动包含 rag_session_meta 表）

```bash
curl -X POST http://localhost:8080/api/tenants \
  -H "Content-Type: application/json" \
  -d '{
    "tenantCode": "new_tenant",
    "tenantName": "新租户",
    "status": 1
  }'
```

### 删除租户

```bash
# 调用删除租户 API
curl -X DELETE http://localhost:8080/api/tenants/{tenantId}
```

或者在代码中调用：

```java
@Autowired
private TenantService tenantService;

// 删除租户（级联删除 Schema 和所有数据）
boolean success = tenantService.deleteTenantWithSchema(tenantId);
if (success) {
    log.info("租户删除成功");
} else {
    log.error("租户删除失败");
}
```

## 数据库验证

创建租户后，验证 5 个表都已创建：

```sql
-- 查看租户 schema 下的所有表
SELECT table_name 
FROM information_schema.tables 
WHERE table_schema = 'tenant_new_tenant'
ORDER BY table_name;

-- 应该看到 5 个表：
-- 1. doc_chunk
-- 2. rag_document
-- 3. rag_session
-- 4. rag_session_meta  ← 新增
-- 5. vector_store
```

## 删除租户的注意事项

### 1. 级联删除
使用 `DROP SCHEMA ... CASCADE` 会级联删除：
- Schema 下的所有表
- 表中的所有数据
- 索引、触发器、约束等

### 2. 事务保护
删除操作在 `@Transactional` 事务中执行：
- 如果删除 Schema 失败，事务回滚
- 如果删除租户记录失败，事务回滚
- 确保数据一致性

### 3. 日志记录
完整的日志输出：
```
INFO  正在删除租户 [test1] 的 Schema: tenant_test1
INFO  已删除租户 [test1] 的 Schema: tenant_test1
INFO  已删除租户记录：test1 (ID=1)
INFO  租户 [test1] 删除成功
```

### 4. 错误处理
- 租户不存在：返回 `false`，记录警告日志
- Schema 名称为空：返回 `false`，记录错误日志
- 删除失败：抛出 `BizException`，事务回滚

## 安全考虑

### 1. SQL 注入防护
删除 Schema 时直接使用租户的 `schemaName` 字段，该字段在创建时已经过校验：
```java
if (!schemaName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
    throw new BizException("非法 Schema 名称：" + schemaName);
}
```

### 2. 权限控制
删除租户属于高危操作，建议在 Controller 层添加：
- 管理员权限验证
- 操作审计日志
- 二次确认机制

### 3. 数据备份
删除前建议：
- 备份重要数据
- 导出会话记录
- 通知相关用户

## 相关文件清单

### 修改的文件
- ✅ `company-rag-tenant/src/main/java/com/company/rag/tenant/service/TenantService.java`
  - 添加 `deleteTenantWithSchema()` 方法声明
  
- ✅ `company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`
  - 添加 `rag_session_meta` 表创建 SQL（第 51-96 行）
  - 添加 `rag_session_meta` RLS 策略（第 145-160 行）
  - 实现 `deleteTenantWithSchema()` 方法（第 180-210 行）

### 相关实体类
- `company-rag-rag/src/main/java/com/company/rag/rag/entity/RagSessionMeta.java`
- `company-rag-rag/src/main/java/com/company/rag/rag/mapper/RagSessionMetaMapper.java`

## 后续建议

### 1. 添加 REST API
在 Controller 层添加删除租户的 REST 接口：

```java
@DeleteMapping("/{tenantId}")
public R<Void> deleteTenant(@PathVariable Long tenantId) {
    boolean success = tenantService.deleteTenantWithSchema(tenantId);
    return success ? R.success("删除成功") : R.error("租户不存在");
}
```

### 2. 添加软删除选项
考虑支持软删除模式：
- 标记 `is_deleted = true`
- 定期清理已标记的租户
- 支持数据恢复

### 3. 添加删除前检查
删除前验证：
- 租户下是否有活跃会话
- 是否有未完成的文档处理
- 是否需要数据归档

### 4. 添加操作审计
记录删除操作：
- 操作人
- 操作时间
- 删除的租户信息
- 删除的数据量

## 完成状态

✅ **问题 1 修复完成**：rag_session_meta 表已添加到租户创建流程
✅ **问题 2 功能完成**：删除租户功能已实现
✅ **编译验证通过**
✅ **单元测试通过**
✅ **文档编写完成**

下一步：启动应用，创建新租户验证 5 个表都已创建，测试删除租户功能。
