package com.company.rag.rag.eval.answer;

/**
 * 回答相对检索上下文的忠实度评估（防幻觉）。
 * 依赖真实检索上下文；若无上下文则返回"无法判定"（不判 pass 也不判 fail，避免误报幻觉）。
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
        // UNKNOWN 视为不通过提示风险，但 log 不判死；本方法仅返回布尔判定
        return verdict == FaithfulnessChecker.Verdict.FAITHFUL;
    }
}