package com.company.rag.rag.router;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PatternRule 测试类
 * 验证规则匹配功能
 */
class PatternRuleTest {

    @Test
    void testDatabaseRuleMatch() {
        // 创建 DATABASE 意图规则
        List<String> patterns = List.of(
            ".*数据库.*",
            ".*查询.*数据.*",
            ".*sql.*",
            ".*select.*from.*"
        );
        
        PatternRule rule = PatternRule.builder()
            .intent(IntentType.DATABASE)
            .patterns(patterns)
            .confidence(0.9)
            .build();
        rule.buildAndCompile();
        
        // 测试匹配
        assertTrue(rule.matches("如何查询数据库中的用户信息"), "应该匹配数据库查询");
        assertTrue(rule.matches("帮我写一个 SQL 查询"), "应该匹配 SQL 查询");
        assertTrue(rule.matches("SELECT * FROM users"), "应该匹配 SELECT 语句");
        
        // 测试不匹配
        assertFalse(rule.matches("今天天气不错"), "不应该匹配无关查询");
    }

    @Test
    void testCodeRuleMatch() {
        // 创建 CODE 意图规则
        List<String> patterns = List.of(
            ".*代码.*",
            ".*怎么实现.*",
            ".*java.*",
            ".*python.*示例.*",
            ".*函数.*怎么写.*"
        );
        
        PatternRule rule = PatternRule.builder()
            .intent(IntentType.CODE)
            .patterns(patterns)
            .confidence(0.85)
            .build();
        rule.buildAndCompile();
        
        // 测试匹配
        assertTrue(rule.matches("这段代码怎么写"), "应该匹配代码查询");
        assertTrue(rule.matches("Java 如何实现多线程"), "应该匹配 Java 实现");
        assertTrue(rule.matches("Python 示例代码"), "应该匹配 Python 示例");
        
        // 测试不匹配
        assertFalse(rule.matches("数据库连接失败"), "不应该匹配数据库错误");
    }

    @Test
    void testChatRuleMatch() {
        // 创建 CHAT 意图规则
        List<String> patterns = List.of(
            ".*你好.*",
            ".*谢谢.*",
            ".*再见.*",
            ".*今天.*",
            ".*天气.*"
        );
        
        PatternRule rule = PatternRule.builder()
            .intent(IntentType.CHAT)
            .patterns(patterns)
            .confidence(0.8)
            .build();
        rule.buildAndCompile();
        
        // 测试匹配
        assertTrue(rule.matches("你好，请问在吗"), "应该匹配问候");
        assertTrue(rule.matches("谢谢你"), "应该匹配感谢");
        assertTrue(rule.matches("今天天气怎么样"), "应该匹配天气查询");
        
        // 测试不匹配
        assertFalse(rule.matches("查询数据库表结构"), "不应该匹配数据库查询");
    }

    @Test
    void testCaseInsensitive() {
        // 测试大小写不敏感
        List<String> patterns = List.of(".*SELECT.*FROM.*");
        
        PatternRule rule = PatternRule.builder()
            .intent(IntentType.DATABASE)
            .patterns(patterns)
            .confidence(0.9)
            .build();
        rule.buildAndCompile();
        
        assertTrue(rule.matches("select * from users"), "小写应该匹配");
        assertTrue(rule.matches("SELECT * FROM USERS"), "大写应该匹配");
        assertTrue(rule.matches("Select * From Users"), "混合大小写应该匹配");
    }

    @Test
    void testMultiplePatterns() {
        // 测试多个模式，只要匹配一个即可
        List<String> patterns = List.of(".*数据库.*", ".*SQL.*", ".*query.*");
        
        PatternRule rule = PatternRule.builder()
            .intent(IntentType.DATABASE)
            .patterns(patterns)
            .confidence(0.9)
            .build();
        rule.buildAndCompile();
        
        assertTrue(rule.matches("我想查询数据库"), "匹配第一个模式");
        assertTrue(rule.matches("帮我写 SQL"), "匹配第二个模式");
        assertTrue(rule.matches("run a query"), "匹配第三个模式");
    }
}
