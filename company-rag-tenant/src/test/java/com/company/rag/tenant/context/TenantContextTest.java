package com.company.rag.tenant.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 租户上下文 TenantContext 的单元测试
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        // 每个测试后清理上下文，防止相互影响
        TenantContext.clear();
    }

    @Test
    void testSetAndGetTenantId_ShouldStoreAndRetrieveCorrectly() {
        // Given
        Long expectedTenantId = 123L;

        // When
        TenantContext.setTenantId(expectedTenantId);
        Long actualTenantId = TenantContext.getTenantId();

        // Then
        assertEquals(expectedTenantId, actualTenantId);
    }

    @Test
    void testSetAndGetTenantCode_ShouldStoreAndRetrieveCorrectly() {
        // Given
        String expectedCode = "tenant-001";

        // When
        TenantContext.setTenantCode(expectedCode);
        String actualCode = TenantContext.getTenantCode();

        // Then
        assertEquals(expectedCode, actualCode);
    }

    @Test
    void testSetAndGetUserId_ShouldStoreAndRetrieveCorrectly() {
        // Given
        Long expectedUserId = 456L;

        // When
        TenantContext.setUserId(expectedUserId);
        Long actualUserId = TenantContext.getUserId();

        // Then
        assertEquals(expectedUserId, actualUserId);
    }

    @Test
    void testSetAndGetSchema_ShouldStoreAndRetrieveCorrectly() {
        // Given
        String expectedSchema = "tenant_001_schema";

        // When
        TenantContext.setSchema(expectedSchema);
        String actualSchema = TenantContext.getSchema();

        // Then
        assertEquals(expectedSchema, actualSchema);
    }

    @Test
    void testClear_ShouldRemoveAllTenantContext() {
        // Given
        TenantContext.setTenantId(1L);
        TenantContext.setTenantCode("code1");
        TenantContext.setUserId(100L);
        TenantContext.setSchema("schema1");

        // When
        TenantContext.clear();

        // Then
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getTenantCode());
        assertNull(TenantContext.getUserId());
        assertNull(TenantContext.getSchema());
    }

    @Test
    void testGetTenantId_WithoutSetting_ShouldReturnNull() {
        // When
        Long tenantId = TenantContext.getTenantId();

        // Then
        assertNull(tenantId);
    }

    @Test
    void testGetTenantCode_WithoutSetting_ShouldReturnNull() {
        // When
        String tenantCode = TenantContext.getTenantCode();

        // Then
        assertNull(tenantCode);
    }

    @Test
    void testGetUserId_WithoutSetting_ShouldReturnNull() {
        // When
        Long userId = TenantContext.getUserId();

        // Then
        assertNull(userId);
    }

    @Test
    void testGetSchema_WithoutSetting_ShouldReturnNull() {
        // When
        String schema = TenantContext.getSchema();

        // Then
        assertNull(schema);
    }

    @Test
    void testSetMultipleValues_ShouldStoreAllCorrectly() {
        // Given
        Long tenantId = 789L;
        String tenantCode = "tenant-789";
        Long userId = 999L;
        String schema = "tenant_789_schema";

        // When
        TenantContext.setTenantId(tenantId);
        TenantContext.setTenantCode(tenantCode);
        TenantContext.setUserId(userId);
        TenantContext.setSchema(schema);

        // Then
        assertEquals(tenantId, TenantContext.getTenantId());
        assertEquals(tenantCode, TenantContext.getTenantCode());
        assertEquals(userId, TenantContext.getUserId());
        assertEquals(schema, TenantContext.getSchema());
    }

    @Test
    void testOverwriteTenantId_ShouldUseLatestValue() {
        // Given
        TenantContext.setTenantId(1L);

        // When
        TenantContext.setTenantId(2L);

        // Then
        assertEquals(2L, TenantContext.getTenantId());
    }

    @Test
    void testThreadSafety_DifferentThreadsShouldHaveIndependentContext() throws InterruptedException {
        // Given
        TenantContext.setTenantId(1L);
        TenantContext.setTenantCode("thread-main");

        // Use CountDownLatch to coordinate threads
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Long> threadTenantId = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> threadTenantCode = new java.util.concurrent.atomic.AtomicReference<>();

        // When: Create a new thread with different context
        Thread thread = new Thread(() -> {
            TenantContext.setTenantId(2L);
            TenantContext.setTenantCode("thread-sub");
            latch.countDown(); // Signal that context is set
            threadTenantId.set(TenantContext.getTenantId());
            threadTenantCode.set(TenantContext.getTenantCode());
        });

        thread.start();
        latch.await(); // Wait for thread to set context
        thread.join();

        // Then: Main thread should have original values
        assertEquals(1L, TenantContext.getTenantId());
        assertEquals("thread-main", TenantContext.getTenantCode());

        // Sub thread should have its own values
        assertEquals(2L, threadTenantId.get());
        assertEquals("thread-sub", threadTenantCode.get());
    }

    @Test
    void testSetNullValues_ShouldHandleNulls() {
        // When
        TenantContext.setTenantId(null);
        TenantContext.setTenantCode(null);
        TenantContext.setUserId(null);
        TenantContext.setSchema(null);

        // Then
        assertNull(TenantContext.getTenantId());
        assertNull(TenantContext.getTenantCode());
        assertNull(TenantContext.getUserId());
        assertNull(TenantContext.getSchema());
    }

    @Test
    void testChineseTenantCode_ShouldPreserveChineseCharacters() {
        // Given
        String chineseTenantCode = "租户 -001";

        // When
        TenantContext.setTenantCode(chineseTenantCode);
        String actualCode = TenantContext.getTenantCode();

        // Then
        assertEquals(chineseTenantCode, actualCode);
    }
}
