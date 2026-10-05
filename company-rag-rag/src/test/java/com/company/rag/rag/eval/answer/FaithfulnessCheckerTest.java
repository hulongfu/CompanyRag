package com.company.rag.rag.eval.answer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * FaithfulnessChecker 忠实度判定的单元测试。
 *
 * 覆盖正常（有据 PASS / 无据 FAIL）、边界（空上下文、citations 门槛）与异常（空回答、
 * 「抱歉」开头）三类场景，验证从「宽松 citations 标记启发式」升级为「正文字符二元组覆盖 +
 * citations 门槛」后的判定语义。
 */
class FaithfulnessCheckerTest {

    private final FaithfulnessChecker checker = new FaithfulnessChecker();

    // ---------- 正常：有据 ----------

    @Test
    void answerGroundedInContext_returnsFaithful() {
        String context = "citations=README.md#0\n"
                + "[来源:README.md] 申请测试环境需要联系运维并提交工单";
        assertEquals(FaithfulnessChecker.Verdict.FAITHFUL,
                checker.check("申请测试环境需要联系运维", context));
    }

    @Test
    void answerPartiallyGrounded_returnsFaithful() {
        // 回答的相邻字符片段在正文中存在足够比例，即使未逐字一致也视为有据
        String context = "citations=请假流程.md#1\n"
                + "[来源:请假流程.md] 请假需提前一天通过OA提交申请并由直属主管审批";
        assertEquals(FaithfulnessChecker.Verdict.FAITHFUL,
                checker.check("请假需要OA提交并找主管审批", context));
    }

    // ---------- 正常：无据 ----------

    @Test
    void answerNotGrounded_returnsUnfaithful() {
        // context 有 citations 声明确实检索到文档，但回答内容与正文无关 → 不忠实（编造）
        String context = "citations=README.md#0\n"
                + "[来源:README.md] 项目采用Spring Boot 3.4与PGVector构建";
        assertEquals(FaithfulnessChecker.Verdict.UNFAITHFUL,
                checker.check("今天天气晴朗适合出游", context));
    }

    // ---------- 边界 ----------

    @Test
    void nullContext_returnsUnknown() {
        assertEquals(FaithfulnessChecker.Verdict.UNKNOWN, checker.check("任意回答", null));
    }

    @Test
    void blankContextNoCitations_returnsUnfaithful() {
        // 有正文但无 citations= 门槛（例如无工具调用的普通对话上下文）→ 不能判定有据
        String context = "普通对话内容，没有任何引用来源";
        assertEquals(FaithfulnessChecker.Verdict.UNFAITHFUL, checker.check("普通对话回答", context));
    }

    // ---------- 异常 ----------

    @Test
    void blankAnswer_returnsUnfaithful() {
        assertEquals(FaithfulnessChecker.Verdict.UNFAITHFUL,
                checker.check("   ", "citations=a.md#0\n[来源:a.md] 内容"));
    }

    @Test
    void apologizeAnswer_returnsUnfaithful() {
        assertEquals(FaithfulnessChecker.Verdict.UNFAITHFUL,
                checker.check("抱歉，暂时无法回答该问题",
                        "citations=a.md#0\n[来源:a.md] 相关知识内容"));
    }
}