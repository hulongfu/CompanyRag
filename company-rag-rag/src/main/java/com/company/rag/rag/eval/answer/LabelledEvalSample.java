package com.company.rag.rag.eval.answer;

import java.time.LocalDateTime;

/**
 * 一条带人工标签的评估样本（spec §3.2.2），供数据集抽取 / 回归重跑使用。
 *
 * 命名二分（spec 十一轮）：
 *  - persistedPass / persistedScore：专指落库的 e.pass / e.score（历史评估结论，供回归对比）；
 *  - avgScore：回归重跑后各维度均分合成的综合得分（用于报表，与 persistedScore 含义不同）。
 * humanLabel：来自 rag_session.feedback（-1 = 👎，1 = 👍，0 未标记已由 SQL WHERE feedback<>0 过滤）。
 */
public record LabelledEvalSample(
        String query,
        String context,
        String answer,
        Long tenantId,
        Boolean persistedPass,
        double persistedScore,
        Short humanLabel,
        Long sessionRowId,
        Long evalId,
        LocalDateTime createTime
) {
}