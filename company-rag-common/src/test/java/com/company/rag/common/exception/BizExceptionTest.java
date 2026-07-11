package com.company.rag.common.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 业务异常 BizException 的单元测试
 */
class BizExceptionTest {

    @Test
    void testConstructor_WithCodeAndMessage_ShouldCreateException() {
        // Given
        int errorCode = 404;
        String errorMsg = "Resource not found";

        // When
        BizException exception = new BizException(errorCode, errorMsg);

        // Then
        assertEquals(errorCode, exception.getCode());
        assertEquals(errorMsg, exception.getMessage());
    }

    @Test
    void testConstructor_WithMessageOnly_ShouldUseDefault400Code() {
        // Given
        String errorMsg = "Bad request";

        // When
        BizException exception = new BizException(errorMsg);

        // Then
        assertEquals(400, exception.getCode());
        assertEquals(errorMsg, exception.getMessage());
    }

    @Test
    void testUnauthorized_ShouldCreate401Exception() {
        // Given
        String errorMsg = "Unauthorized access";

        // When
        BizException exception = BizException.unauthorized(errorMsg);

        // Then
        assertEquals(401, exception.getCode());
        assertEquals(errorMsg, exception.getMessage());
    }

    @Test
    void testForbidden_ShouldCreate403Exception() {
        // Given
        String errorMsg = "Access forbidden";

        // When
        BizException exception = BizException.forbidden(errorMsg);

        // Then
        assertEquals(403, exception.getCode());
        assertEquals(errorMsg, exception.getMessage());
    }

    @Test
    void testNotFound_ShouldCreate404Exception() {
        // Given
        String errorMsg = "Resource not found";

        // When
        BizException exception = BizException.notFound(errorMsg);

        // Then
        assertEquals(404, exception.getCode());
        assertEquals(errorMsg, exception.getMessage());
    }

    @Test
    void testException_WithNullMessage_ShouldHandleNullMessage() {
        // When
        BizException exception = new BizException(500, null);

        // Then
        assertEquals(500, exception.getCode());
        assertNull(exception.getMessage());
    }

    @Test
    void testException_WithEmptyMessage_ShouldHandleEmptyMessage() {
        // When
        BizException exception = new BizException(400, "");

        // Then
        assertEquals(400, exception.getCode());
        assertEquals("", exception.getMessage());
    }

    @Test
    void testException_IsRuntimeException_ShouldBeUnchecked() {
        // When
        BizException exception = new BizException("Test exception");

        // Then
        assertInstanceOf(RuntimeException.class, exception);
    }

    @Test
    void testException_WithChineseMessage_ShouldPreserveChineseMessage() {
        // Given
        String errorMsg = "参数错误：用户名不能为空";

        // When
        BizException exception = new BizException(400, errorMsg);

        // Then
        assertEquals(400, exception.getCode());
        assertEquals(errorMsg, exception.getMessage());
    }

    @Test
    void testException_CanBeThrownAndCaught() {
        // Given
        BizException expectedException = new BizException(400, "Test error");

        // When & Then
        BizException thrownException = assertThrows(BizException.class, () -> {
            throw expectedException;
        });
        assertEquals(expectedException.getCode(), thrownException.getCode());
        assertEquals(expectedException.getMessage(), thrownException.getMessage());
    }
}
