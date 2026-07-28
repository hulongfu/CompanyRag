# 实现总结：租户全文检索自动初始化

## 任务描述

实现创建租户时自动为该租户的 Schema 执行全文检索初始化 SQL，无需手动执行迁移脚本。

## 实现内容

### 1. 代码修改

**文件**：`company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`

**修改位置**：`createTenantSchema()` 方法，第 114-141 行

**修改内容**：在创建索引后、启用 RLS 前，插入全文检索初始化逻辑：

```java
// 4. 初始化全文检索支持（添加 tsvector 列、索引、触发器）
String initFullTextSearchSql = """
    -- 添加 tsvector 列用于全文检索
    ALTER TABLE %1$s.vector_store ADD COLUMN IF NOT EXISTS content_tsv tsvector;
    
    -- 创建 GIN 索引加速全文检索
    CREATE INDEX IF NOT EXISTS idx_%1$s_vector_store_content_tsv 
        ON %1$s.vector_store USING GIN (content_tsv);
    
    -- 创建触发器自动更新 tsvector
    DROP TRIGGER IF EXISTS tsvectorupdate_%1$s ON %1$s.vector_store;
    CREATE TRIGGER tsvectorupdate_%1$s 
        BEFORE INSERT OR UPDATE ON %1$s.vector_store
        FOR EACH ROW EXECUTE FUNCTION
        tsvector_update_trigger(content_tsv, 'pg_catalog.simple', content);
    
    -- 初始化现有数据的 tsvector
    UPDATE %1$s.vector_store SET content_tsv = to_tsvector('pg_catalog.simple', content);
    
    -- 启用 pg_trgm 扩展（用于模糊匹配）
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
    
    -- 创建 trgm 索引加速模糊匹配
    CREATE INDEX IF NOT EXISTS idx_%1$s_vector_store_content_trgm 
        ON %1$s.vector_store USING GIN (content gin_trgm_ops);
    """.formatted(schemaName);
jdbcTemplate.execute(initFullTextSearchSql);
log.info("为租户 [{}] 初始化全文检索支持：content_tsv 列、GIN 索引、触发器、trgm 索引", schemaName);
```

### 2. 初始化内容

每次创建新租户时，系统会自动执行以下 SQL：

1. ✅ **添加 content_tsv 列**（tsvector 类型）
2. ✅ **创建全文检索 GIN 索引**（加速 `@@` 查询）
3. ✅ **创建触发器**（INSERT/UPDATE 时自动更新 content_tsv）
4. ✅ **初始化现有数据**（为已有 content 填充 tsvector）
5. ✅ **启用 pg_trgm 扩展**（支持模糊匹配）
6. ✅ **创建 trgm GIN 索引**（加速 `LIKE` 模糊查询）

### 3. 测试验证

**测试文件**：`company-rag-tenant/src/test/java/com/company/rag/tenant/service/TenantServiceIntegrationTest.java`

**测试结果**：
```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 4. 文档

**文档文件**：`docs/tenant-fulltext-search.md`

包含：
- 功能说明
- 实现位置
- 初始化 SQL 详情
- 测试步骤（API 调用 + 数据库验证）
- 日志输出示例
- 与迁移脚本的关系
- 注意事项

## 使用方式

### 创建新租户

调用现有的创建租户 API 即可，系统会自动执行全文检索初始化：

```bash
curl -X POST http://localhost:8080/api/tenants \
  -H "Content-Type: application/json" \
  -d '{
    "tenantCode": "new_tenant",
    "tenantName": "新租户",
    "status": 1
  }'
```

### 验证初始化成功

查看应用日志，应该看到：

```
INFO  为租户 [new_tenant] 初始化全文检索支持：content_tsv 列、GIN 索引、触发器、trgm 索引
INFO  为租户 [new_tenant] 创建独立 Schema 完成：tenant_new_tenant | 已创建业务表和 RLS 策略
```

### 数据库验证

在 PostgreSQL 中执行：

```sql
-- 检查列是否存在
SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_schema = 'tenant_new_tenant' 
  AND table_name = 'vector_store' 
  AND column_name = 'content_tsv';

-- 检查索引是否存在
SELECT indexname FROM pg_indexes 
WHERE schemaname = 'tenant_new_tenant' 
  AND tablename = 'vector_store';

-- 检查触发器是否存在
SELECT trigger_name FROM information_schema.triggers 
WHERE trigger_schema = 'tenant_new_tenant' 
  AND trigger_name LIKE '%tsvector%';
```

## 与现有功能的关系

### 现有迁移脚本

`sql/hybrid-search-schema-migration.sql` 仍然有效，用于为**现有租户**手动添加全文检索支持。

### 新建租户

本功能实现后，**新建租户**会自动执行类似的 SQL，无需手动运行迁移脚本。

### 全文检索器

`FullTextRetriever.java` 使用 `content_tsv` 列进行全文检索，现在新租户会自动拥有该列。

## 技术细节

### SQL 格式化

使用 Java 文本块（text block）和 `String.formatted()` 方法，确保 SQL 可读性和安全性：

```java
String sql = """
    ALTER TABLE %1$s.vector_store ADD COLUMN IF NOT EXISTS content_tsv tsvector;
    """.formatted(schemaName);
```

### 事务保护

`createTenantSchema()` 方法已标注 `@Transactional`，全文检索初始化 SQL 会在同一事务中执行，失败会回滚。

### 日志记录

使用 SLF4J 记录关键步骤，便于调试和监控。

## 注意事项

1. **pg_trgm 扩展权限**：创建扩展需要 superuser 或 CREATE 权限
2. **性能影响**：GIN 索引略微增加写入时间，但大幅提升查询性能
3. **存储空间**：content_tsv 列额外占用约 20-30% 存储空间
4. **向后兼容**：使用 `IF NOT EXISTS` 和 `DROP IF EXISTS` 确保幂等性

## 编译和测试

```bash
# 编译
mvn clean install -DskipTests -pl company-rag-tenant -am

# 运行测试
mvn test -Dtest=TenantServiceIntegrationTest -pl company-rag-tenant
```

## 相关文件清单

- ✅ `company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java`（已修改）
- ✅ `company-rag-tenant/src/test/java/com/company/rag/tenant/service/TenantServiceIntegrationTest.java`（已更新）
- ✅ `docs/tenant-fulltext-search.md`（已创建）
- ✅ `docs/implementation-summary.md`（本文档）

## 完成状态

✅ **功能实现完成**
✅ **单元测试通过**
✅ **文档编写完成**

下一步：启动应用，创建新租户，验证全文检索功能正常工作。
