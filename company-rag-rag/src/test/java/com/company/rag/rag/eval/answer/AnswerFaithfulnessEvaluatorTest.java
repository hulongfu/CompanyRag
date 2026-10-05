package com.company.rag.rag.eval.answer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AnswerFaithfulnessEvaluator 忠实度评估测试。
 *
 * <p>核心语义：将 {@link FaithfulnessChecker} 三态判定映射为布尔，仅 FAITHFUL 判通过；
 * UNKNOWN / UNFAITHFUL 一律判不通过（防幻觉优先，布尔接口无法表达三态）。
 * 本测试用真实 checker 注入，与生产装配一致，锁定「UNKNOWN→false」的安全护栏，
 * 防止未来回归为「上下文含 citations= 即忠实」的宽松启发式。
 */
class AnswerFaithfulnessEvaluatorTest {

    private AnswerFaithfulnessEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AnswerFaithfulnessEvaluator(new FaithfulnessChecker());
    }

    // ---------- 正常：忠实 ----------

    @Test
    void passes_whenAnswerGroundedInContext() {
        String context = "citations=README.md#0\n"
                + "[来源:README.md] 申请测试环境需要联系运维并提交工单";
        assertTrue(evaluator.evaluate("怎么申请测试环境", context, "申请测试环境需要联系运维"));
    }

    // ---------- 三态映射：UNKNOWN / UNFAITHFUL 一律不通过 ----------

    @Test
    void fails_whenContextIsNull() {
        // checker 返回 UNKNOWN（上下文缺失）→ 防幻觉优先判不通过
        assertFalse(evaluator.evaluate("question", null, "任意回答"));
    }

    @Test
    void fails_whenContextIsBlank() {
        // 空白上下文 → UNKNOWN → false
        assertFalse(evaluator.evaluate("question", "   ", "任意回答"));
    }

    @Test
    void fails_whenContextHasNoCitations() {
        // 普通对话上下文无 citations= 声明 → UNFAITHFUL → false（不能判定有据）
        String context = "普通对话内容，没有任何引用来源";
        assertFalse(evaluator.evaluate("question", context, "普通对话回答"));
    }

    @Test
    void fails_whenAnswerNotGroundedInBody() {
        // 有 citations 声明但回答与正文无关 → UNFAITHFUL → false（编造）
        String context = "citations=README.md#0\n"
                + "[来源:README.md] 项目采用 Spring Boot 3.4 与 PGVector 构建";
        assertFalse(evaluator.evaluate("question", context, "今天天气晴朗适合出游"));
    }

    @Test
    void fails_whenAnswerIsBlank() {
        String context = "citations=a.md#0\n[来源:a.md] 相关内容";
        assertFalse(evaluator.evaluate("question", context, "   "));
    }

    @Test
    void fails_whenAnswerStartsWithApology() {
        String context = "citations=a.md#0\n[来源:a.md] 相关知识内容";
        assertFalse(evaluator.evaluate("question", context, "抱歉，暂时无法回答该问题"));
    }
}
