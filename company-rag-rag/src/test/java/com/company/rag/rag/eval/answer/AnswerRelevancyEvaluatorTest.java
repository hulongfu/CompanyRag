package com.company.rag.rag.eval.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AnswerRelevancyEvaluator 相关性评估测试
 * 覆盖中文（无空格）查询、英文空格查询、兜底回答、空入参等场景。
 */
class AnswerRelevancyEvaluatorTest {

    private AnswerRelevancyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AnswerRelevancyEvaluator();
    }

    @Test
    void relevant_whenChineseQueryNoSpaces_directlyAnswered() {
        // 回归：评估页从会话带入的中文无空格查询，回答直接命中主题应判为相关
        String query = "Spring中有哪些不同的通知类型？请根据知识库中的文档回答";
        String answer = "根据知识库文档（来源：《龙虎道经之java.txt》），Spring 中的通知（Advice）主要有以下 5 种类型："
                + "前置通知 @Before、返回之后通知 @AfterReturning、抛出异常后通知 @AfterThrowing、后置通知 @After、围绕通知 @Around。";
        assertTrue(evaluator.evaluate(query, null, answer));
    }

    @Test
    void relevant_whenSpacedEnglishQuery() {
        assertTrue(evaluator.evaluate("how to reset password", null, "You can reset the password via settings"));
    }

    @Test
    void irrelevant_whenAnswerDoesNotAddressQuery() {
        String query = "Spring中有哪些不同的通知类型？请根据知识库中的文档回答";
        assertFalse(evaluator.evaluate(query, null, "今天天气不错，适合出门散步。"));
    }

    @Test
    void irrelevant_whenApologyFallback() {
        assertFalse(evaluator.evaluate("Spring中有哪些不同的通知类型", null, "抱歉，我不知道。"));
    }

    @Test
    void relevant_whenBlankQuery() {
        // 无查询条件时不做否定判定，视为相关（与上游取空问题一致）
        assertTrue(evaluator.evaluate("", null, "任何回答"));
    }

    @Test
    void irrelevant_whenBlankAnswer() {
        assertFalse(evaluator.evaluate("question", null, "   "));
    }

    @Test
    void relevant_whenNullAnswerStartsWithNothing() {
        // answer 为 null 直接判不相关
        assertFalse(evaluator.evaluate("question", null, null));
    }
}