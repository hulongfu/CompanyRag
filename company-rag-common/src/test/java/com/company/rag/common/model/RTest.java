package com.company.rag.common.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一响应体 R<T> 的单元测试
 */
class RTest {

    @Test
    void testOk_WithData_ShouldReturnSuccessResponse() {
        // Given
        String testData = "test data";

        // When
        R<String> result = R.ok(testData);

        // Then
        assertEquals(200, result.getCode());
        assertEquals("success", result.getMsg());
        assertEquals(testData, result.getData());
    }

    @Test
    void testOk_WithoutData_ShouldReturnSuccessResponseWithNullData() {
        // When
        R<Void> result = R.ok();

        // Then
        assertEquals(200, result.getCode());
        assertEquals("success", result.getMsg());
        assertNull(result.getData());
    }

    @Test
    void testFail_WithCodeAndMessage_ShouldReturnErrorResponse() {
        // Given
        int errorCode = 404;
        String errorMsg = "Not found";

        // When
        R<Void> result = R.fail(errorCode, errorMsg);

        // Then
        assertEquals(errorCode, result.getCode());
        assertEquals(errorMsg, result.getMsg());
        assertNull(result.getData());
    }

    @Test
    void testFail_WithMessageOnly_ShouldReturnDefault400Error() {
        // Given
        String errorMsg = "Bad request";

        // When
        R<Void> result = R.fail(errorMsg);

        // Then
        assertEquals(400, result.getCode());
        assertEquals(errorMsg, result.getMsg());
        assertNull(result.getData());
    }

    @Test
    void testOk_WithComplexData_ShouldPreserveDataStructure() {
        // Given
        TestData complexData = new TestData("name", 25);

        // When
        R<TestData> result = R.ok(complexData);

        // Then
        assertEquals(200, result.getCode());
        assertEquals("success", result.getMsg());
        assertEquals(complexData, result.getData());
        assertEquals("name", result.getData().getName());
        assertEquals(25, result.getData().getAge());
    }

    @Test
    void testOk_WithNullData_ShouldAllowNullData() {
        // When
        R<String> result = R.ok(null);

        // Then
        assertEquals(200, result.getCode());
        assertEquals("success", result.getMsg());
        assertNull(result.getData());
    }

    @Test
    void testFail_WithEmptyMessage_ShouldAllowEmptyMessage() {
        // When
        R<Void> result = R.fail(500, "");

        // Then
        assertEquals(500, result.getCode());
        assertEquals("", result.getMsg());
    }

    @Test
    void testFail_WithSpecialCharactersInMessage_ShouldPreserveMessage() {
        // Given
        String errorMsg = "错误：特殊字符 @#$%";

        // When
        R<Void> result = R.fail(400, errorMsg);

        // Then
        assertEquals(400, result.getCode());
        assertEquals(errorMsg, result.getMsg());
    }

    @Test
    void testOk_WithListData_ShouldPreserveListData() {
        // Given
        java.util.List<String> listData = java.util.List.of("item1", "item2", "item3");

        // When
        R<java.util.List<String>> result = R.ok(listData);

        // Then
        assertEquals(200, result.getCode());
        assertEquals("success", result.getMsg());
        assertEquals(3, result.getData().size());
        assertEquals("item1", result.getData().get(0));
    }

    /**
     * 测试用的数据类
     */
    static class TestData {
        private String name;
        private int age;

        TestData(String name, int age) {
            this.name = name;
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public int getAge() {
            return age;
        }
    }
}
