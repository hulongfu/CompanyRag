package com.company.rag.rag.eval.answer;

import java.util.Map;

/**
 * 单条回答的评估结果。
 * pass: 综合判定是否通过；score: 综合评分（0~1 或 0~100，由实现约定）；
 * dimensionScores: 各维度（relevancy/correctness/faithfulness）分数明细。
 */
public class AnswerEvalResult {
    private final String query;
    private final String context;
    private final String answer;
    private final boolean pass;
    private final double score;
    private final Map<String, Double> dimensionScores;

    public AnswerEvalResult(String query, String context, String answer,
                            boolean pass, double score, Map<String, Double> dimensionScores) {
        this.query = query;
        this.context = context;
        this.answer = answer;
        this.pass = pass;
        this.score = score;
        this.dimensionScores = dimensionScores;
    }

    public String query() { return query; }
    public String context() { return context; }
    public String answer() { return answer; }
    public boolean pass() { return pass; }
    public double score() { return score; }
    public Map<String, Double> dimensionScores() { return dimensionScores; }

    /** 综合各维度是否全部通过的快捷方法（供 service 聚合用） */
    public static boolean allPass(Map<String, Boolean> passes) {
        return passes != null && passes.values().stream().allMatch(Boolean::booleanValue);
    }
}