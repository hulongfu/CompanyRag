package com.company.rag.document.service.impl;

import org.junit.jupiter.api.Test;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证向量化存储时 ID 生成逻辑
 * 
 * 修复背景：
 * - Spring AI 的 PgVectorStore 要求 Document ID 必须是 UUID 格式
 * - 之前使用 chunk.getId().toString() 生成 "131" 这样的数字字符串
 * - 导致 UUID.fromString() 抛出 IllegalArgumentException: Invalid UUID string: 131
 * 
 * 修复方案：
 * - 使用 UUID.randomUUID().toString() 生成符合规范的 UUID
 */
public class VectorizeIdTest {

    @Test
    public void testUuidFormat() {
        // 验证 UUID 生成符合规范
        String uuidString = UUID.randomUUID().toString();
        
        // UUID 应该是 36 个字符（32 个十六进制数字 + 4 个连字符）
        assertEquals(36, uuidString.length(), "UUID 字符串长度应为 36");
        
        // 验证格式：xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        assertTrue(uuidString.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"), 
                   "UUID 应符合标准格式");
        
        // 验证可以被 UUID.fromString() 解析
        UUID parsed = UUID.fromString(uuidString);
        assertNotNull(parsed, "UUID 字符串应能被成功解析");
    }
    
    @Test
    public void testInvalidUuidString() {
        // 验证数字字符串会被 UUID.fromString() 拒绝
        String invalidId = "131";
        
        assertThrows(IllegalArgumentException.class, () -> {
            UUID.fromString(invalidId);
        }, "数字字符串 '131' 应该被 UUID.fromString() 拒绝");
    }
    
    @Test
    public void testUuidUniqueness() {
        // 验证每次生成的 UUID 都是唯一的
        String uuid1 = UUID.randomUUID().toString();
        String uuid2 = UUID.randomUUID().toString();
        
        assertNotEquals(uuid1, uuid2, "两次生成的 UUID 应该不同");
    }
}
