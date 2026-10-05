package com.company.rag.rag.eval.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AnswerCorrectnessEvaluator 正确性评估测试：钉住「非空、长度≥10、非"抱歉"开头」判定，
 * 覆盖正常通过、长度边界（9/10）与防御性入参（null/空白/空串/抱歉前缀）场景。
 */
class AnswerCorrectnessEvaluatorTest {

    private AnswerCorrectnessEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AnswerCorrectnessEvaluator();
    }

    // ---------- 正常通过 ----------

    @Test
    void passes_whenAnswerHasSubstance_overMinLength() {
        // 长度远超 10 的中文实质回答 → 通过
        String answer = "Spring 的通知类型共有五种：Before、AfterReturning、AfterThrowing、After、Around。";
        assertTrue(evaluator.evaluate("question", null, answer));
    }

    // ---------- 边界 ----------

    @Test
    void passes_whenAnswerLengthExactlyTen() {
        // 阈值恰为 10 → 通过（>= 10）
        assertTrue(evaluator.evaluate("question", null, "一二三四五六七八九十"));
    }

    @Test
    void fails_whenAnswerLengthNine() {
        // 阈值下方：9 字符 → 不通过
        assertFalse(evaluator.evaluate("question", null, "一二三四五六七八九"));
    }

    @Test
    void passes_whenAnswerEndsWithApologyButLengthOk() {
        // 仅判定前缀开头，不以"抱歉"开头且长度足够 → 通过
        assertTrue(evaluator.evaluate("question", null, "这个报销流程可以，抱歉不适用。"));
    }

    // ---------- 防御性入参 ----------

    @Test
    void fails_whenAnswerIsNull() {
        assertFalse(evaluator.evaluate("question", null, null));
    }

    @Test
    void fails_whenAnswerIsBlank() {
        assertFalse(evaluator.evaluate("question", null, "     "));
    }

    @Test
    void fails_whenAnswerIsEmpty() {
        assertFalse(evaluator.evaluate("question", null, ""));
    }

    @Test
    void fails_whenAnswerStartsWithApology() {
        // "抱歉"前缀 → 不通过（兜底回答）
        assertFalse(evaluator.evaluate("question", null, "抱歉，我不知道该问题的答案。"));
    }

    @Test
    void fails_whenAnswerIsJustApology() {
        // 仅"抱歉"二字：前缀拦截（且长度 2 < 10 双重拦截）
        assertFalse(evaluator.evaluate("question", null, "抱歉"));
    }
}
