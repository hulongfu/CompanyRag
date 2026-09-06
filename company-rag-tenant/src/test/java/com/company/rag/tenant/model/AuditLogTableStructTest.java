package com.company.rag.tenant.model;

import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计日志表结构契约测试
 * <p>
 * 断言：
 * 1. {@link AuditLog} 的 @TableName 必须显式限定 `public.audit_log`（规避 search_path 残留硬伤 2）
 * 2. 实体的关键字段与 SQL 列一一对应（表结构契约，禁止猜字段名）
 */
class AuditLogTableStructTest {

    @Test
    void tableNameShouldExplicitlyTargetPublicSchema() {
        TableName tableName = AuditLog.class.getAnnotation(TableName.class);
        assertEquals("public.audit_log", tableName.value(),
                "@TableName 必须显式限定 public.audit_log（审计表为平台级 public 表）");
    }

    @Test
    void entityFieldsShouldMatchSqlColumns() {
        // DDL 列名与实体字段名（驼峰）一一对应
        String[] expectedFields = {
                "id",
                "tenantId",      // tenant_id
                "userId",        // user_id
                "actionType",    // action_type
                "targetType",    // target_type
                "targetId",      // target_id
                "detail",        // detail
                "ipAddress",     // ip_address
                "createdAt"      // created_at
        };
        Set<String> declaredFields = new HashSet<>();
        for (Field f : AuditLog.class.getDeclaredFields()) {
            declaredFields.add(f.getName());
        }
        for (String field : expectedFields) {
            assertTrue(declaredFields.contains(field),
                    "AuditLog 应含字段[" + field + "]，与 SQL 列映射");
        }
        assertEquals(Arrays.asList(expectedFields).size(), declaredFields.size(),
                "AuditLog 不应有多余字段，字段契约应与 DDL 列严格一致");
    }
}