package com.company.rag.rag.eval.answer;

/**
 * 回答相对检索上下文的忠实度评估（防幻觉）。
 * 依赖真实检索上下文；本接口为布尔判定，故 UNKNOWN（上下文缺失或无法判定）
 * 统一映射为"未通过"（false），避免在缺少依据时误判为忠实（防幻觉优先）。
 * 判定逻辑复用 FaithfulnessChecker，不与 reflection 各写一套。
 */
public class AnswerFaithfulnessEvaluator implements AnswerEvaluator {

    private final FaithfulnessChecker checker;

    public AnswerFaithfulnessEvaluator(FaithfulnessChecker checker) {
        this.checker = checker;
    }

    @Override
    public String dimensionName() {
        return "faithfulness";
    }

    @Override
    public boolean evaluate(String query, String context, String answer) {
        FaithfulnessChecker.Verdict verdict = checker.check(answer, context);
        // 仅 FAITHFUL 判通过；UNKNOWN/UNFAITHFUL 均判未通过（防幻觉优先，布尔接口无法表达三态）
        return verdict == FaithfulnessChecker.Verdict.FAITHFUL;
    }
}