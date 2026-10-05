package com.company.rag.rag.eval.answer;

/**
 * 单次评估的判定结果（spec §3.3，doEvaluate 唯一入口的返回）。
 *
 * <p>仅含三维布尔 + 综合 pass + score，<b>不含 dimensionScores map</b>：
 * 落库侧三列由三维布尔按 true→1.0/false→0.0 派生（不另存独立维度分），
 * 保证落库列与 doEvaluate 判定严格一致。
 */
public record EvalDecision(
        boolean relevancy,
        boolean correctness,
        boolean faithfulness,
        boolean pass,
        double score
) {
    @Override
    public String toString() {
        return "EvalDecision{relevancy=" + relevancy
                + ", correctness=" + correctness
                + ", faithfulness=" + faithfulness
                + ", pass=" + pass
                + ", score=" + score + "}";
    }
}
