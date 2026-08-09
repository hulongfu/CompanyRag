package com.company.rag.tenant.context;

import com.company.rag.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TenantSqlHelper 单元测试
 * 覆盖 null/非法/合法三态
 */
@DisplayName("TenantSqlHelper 单元测试")
class TenantSqlHelperTest {

    @AfterEach
    void tearDown() {
        // 清理租户上下文，避免污染其他测试
        TenantContext.clear();
    }

    @Nested
    @DisplayName("requireSchema() 测试")
    class RequireSchemaTests {

        @Test
        @DisplayName("未设置租户上下文时抛出 BizException")
        void shouldThrowExceptionWhenTenantContextNotSet() {
            // Arrange: 不设置租户上下文
            TenantContext.clear();

            // Act & Assert
            BizException exception = assertThrows(BizException.class, TenantSqlHelper::requireSchema);
            assertEquals("未设置租户上下文", exception.getMessage());
        }

        @Test
        @DisplayName("租户 schema 为 null 时抛出 BizException")
        void shouldThrowExceptionWhenSchemaIsNull() {
            // Arrange
            TenantContext.setSchema(null);

            // Act & Assert
            BizException exception = assertThrows(BizException.class, TenantSqlHelper::requireSchema);
            assertEquals("未设置租户上下文", exception.getMessage());
        }

        @Test
        @DisplayName("租户 schema 为空字符串时抛出 BizException")
        void shouldThrowExceptionWhenSchemaIsEmptyString() {
            // Arrange
            TenantContext.setSchema("");

            // Act & Assert
            BizException exception = assertThrows(BizException.class, TenantSqlHelper::requireSchema);
            assertEquals("未设置租户上下文", exception.getMessage());
        }

        @Test
        @DisplayName("租户 schema 为空白字符串时抛出 BizException")
        void shouldThrowExceptionWhenSchemaIsBlank() {
            // Arrange
            TenantContext.setSchema("   ");

            // Act & Assert
            BizException exception = assertThrows(BizException.class, TenantSqlHelper::requireSchema);
            assertEquals("未设置租户上下文", exception.getMessage());
        }

        @Test
        @DisplayName("租户 schema 合法时正常返回")
        void shouldReturnSchemaWhenValid() {
            // Arrange
            TenantContext.setSchema("tenant_1");

            // Act
            String schema = TenantSqlHelper.requireSchema();

            // Assert
            assertEquals("tenant_1", schema);
        }

        @Test
        @DisplayName("租户 schema 包含下划线时正常返回")
        void shouldReturnSchemaWhenContainsUnderscore() {
            // Arrange
            TenantContext.setSchema("company_abc_123");

            // Act
            String schema = TenantSqlHelper.requireSchema();

            // Assert
            assertEquals("company_abc_123", schema);
        }
    }

    @Nested
    @DisplayName("getSchemaOrNull() 测试")
    class GetSchemaOrNullTests {

        @Test
        @DisplayName("未设置租户上下文时返回 null")
        void shouldReturnNullWhenTenantContextNotSet() {
            // Arrange
            TenantContext.clear();

            // Act
            String schema = TenantSqlHelper.getSchemaOrNull();

            // Assert
            assertNull(schema);
        }

        @Test
        @DisplayName("租户 schema 合法时正常返回")
        void shouldReturnSchemaWhenValid() {
            // Arrange
            TenantContext.setSchema("tenant_1");

            // Act
            String schema = TenantSqlHelper.getSchemaOrNull();

            // Assert
            assertEquals("tenant_1", schema);
        }
    }

    @Nested
    @DisplayName("getQualifiedTableName() 测试")
    class GetQualifiedTableNameTests {

        @Test
        @DisplayName("schema 为 null 时抛出 BizException")
        void shouldThrowExceptionWhenSchemaIsNull() {
            // Act & Assert
            BizException exception = assertThrows(
                BizException.class,
                () -> TenantSqlHelper.getQualifiedTableName(null, "vector_store")
            );
            assertEquals("租户 schema 不能为空", exception.getMessage());
        }

        @Test
        @DisplayName("schema 为空字符串时抛出 BizException")
        void shouldThrowExceptionWhenSchemaIsEmpty() {
            // Act & Assert
            BizException exception = assertThrows(
                BizException.class,
                () -> TenantSqlHelper.getQualifiedTableName("", "vector_store")
            );
            assertEquals("租户 schema 不能为空", exception.getMessage());
        }

        @Test
        @DisplayName("schema 包含非法字符时抛出 BizException")
        void shouldThrowExceptionWhenSchemaContainsInvalidChars() {
            // Act & Assert
            BizException exception = assertThrows(
                BizException.class,
                () -> TenantSqlHelper.getQualifiedTableName("tenant-1", "vector_store")
            );
            assertTrue(exception.getMessage().contains("非法租户 schema 名称"));
        }

        @Test
        @DisplayName("schema 包含数字开头时抛出 BizException")
        void shouldThrowExceptionWhenSchemaStartsWithNumber() {
            // Act & Assert
            BizException exception = assertThrows(
                BizException.class,
                () -> TenantSqlHelper.getQualifiedTableName("1tenant", "vector_store")
            );
            assertTrue(exception.getMessage().contains("非法租户 schema 名称"));
        }

        @Test
        @DisplayName("表名为 null 时抛出 BizException")
        void shouldThrowExceptionWhenTableNameIsNull() {
            // Act & Assert
            BizException exception = assertThrows(
                BizException.class,
                () -> TenantSqlHelper.getQualifiedTableName("tenant_1", null)
            );
            assertEquals("表名不能为空", exception.getMessage());
        }

        @Test
        @DisplayName("表名为空字符串时抛出 BizException")
        void shouldThrowExceptionWhenTableNameIsEmpty() {
            // Act & Assert
            BizException exception = assertThrows(
                BizException.class,
                () -> TenantSqlHelper.getQualifiedTableName("tenant_1", "")
            );
            assertEquals("表名不能为空", exception.getMessage());
        }

        @Test
        @DisplayName("表名包含非法字符时抛出 BizException")
        void shouldThrowExceptionWhenTableNameContainsInvalidChars() {
            // Act & Assert
            BizException exception = assertThrows(
                BizException.class,
                () -> TenantSqlHelper.getQualifiedTableName("tenant_1", "vector-store")
            );
            assertTrue(exception.getMessage().contains("非法表名"));
        }

        @Test
        @DisplayName("schema 和表名都合法时返回正确格式")
        void shouldReturnQualifiedTableNameWhenValid() {
            // Act
            String result = TenantSqlHelper.getQualifiedTableName("tenant_1", "vector_store");

            // Assert
            assertEquals("tenant_1.vector_store", result);
        }

        @Test
        @DisplayName("schema 和表名都合法时返回正确格式（复杂名称）")
        void shouldReturnQualifiedTableNameWhenValidComplexNames() {
            // Act
            String result = TenantSqlHelper.getQualifiedTableName("company_abc_123", "document_chunk_2024");

            // Assert
            assertEquals("company_abc_123.document_chunk_2024", result);
        }
    }
}
