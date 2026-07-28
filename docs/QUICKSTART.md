# 快速参考：租户全文检索功能

## 功能已实现 ✅

创建新租户时，系统会自动为该租户的 Schema 初始化全文检索支持。

## 核心代码位置

```
company-rag-tenant/src/main/java/com/company/rag/tenant/service/impl/TenantServiceImpl.java
```

方法：`createTenantSchema()`，第 114-141 行

## 初始化内容（6 项）

1. ✅ `content_tsv` 列（tsvector 类型）
2. ✅ 全文检索 GIN 索引
3. ✅ tsvector 更新触发器
4. ✅ 现有数据初始化
5. ✅ pg_trgm 扩展
6. ✅ trgm 模糊匹配索引

## 快速测试

### 1. 创建租户
```bash
curl -X POST http://localhost:8080/api/tenants \
  -H "Content-Type: application/json" \
  -d '{"tenantCode": "test1", "tenantName": "测试租户", "status": 1}'
```

### 2. 查看日志
```
INFO  为租户 [test1] 初始化全文检索支持：content_tsv 列、GIN 索引、触发器、trgm 索引
```

### 3. 数据库验证
```sql
-- 验证列
SELECT column_name FROM information_schema.columns 
WHERE table_schema = 'tenant_test1' AND column_name = 'content_tsv';

-- 验证索引
SELECT indexname FROM pg_indexes 
WHERE schemaname = 'tenant_test1' AND indexname LIKE '%content%';

-- 验证触发器
SELECT trigger_name FROM information_schema.triggers 
WHERE trigger_schema = 'tenant_test1' AND trigger_name LIKE '%tsvector%';
```

## 编译命令

```bash
# 编译
mvn clean install -DskipTests -pl company-rag-tenant -am

# 测试
mvn test -Dtest=TenantServiceIntegrationTest -pl company-rag-tenant
```

## 相关文档

- 详细说明：`docs/tenant-fulltext-search.md`
- 实现总结：`docs/implementation-summary.md`
- 迁移脚本：`sql/hybrid-search-schema-migration.sql`

## 注意事项

⚠️ 创建租户的数据库用户需要有 CREATE EXTENSION 权限
⚠️ GIN 索引会增加写入时间，但大幅提升查询性能
⚠️ content_tsv 列额外占用约 20-30% 存储空间

## 常见问题

**Q: 现有租户怎么办？**
A: 手动执行 `sql/hybrid-search-schema-migration.sql` 脚本

**Q: 如何禁用全文检索初始化？**
A: 修改 `TenantServiceImpl.java` 第 114-141 行，注释掉即可

**Q: 初始化失败会怎样？**
A: 事务回滚，租户创建失败，需要排查数据库权限问题
