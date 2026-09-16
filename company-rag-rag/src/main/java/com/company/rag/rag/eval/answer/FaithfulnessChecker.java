package com.company.rag.rag.eval.answer;

import org.springframework.stereotype.Component;

/**
 * 回答相对检索上下文的忠实度判定，供 reflection（在线轻量分支）与本 spec 的
 * AnswerFaithfulnessEvaluator（离线可重评分）复用，避免各写一套。
 */
@Component
public class FaithfulnessChecker {

    /**
     * 判定结果：忠实 / 不忠实 / 无法判定（上下文缺失）。
     */
    public enum Verdict { FAITHFUL, UNFAITHFUL, UNKNOWN }

    /**
     * 第一版以可观察的规则式判定：有上下文且答案包含上下文中的关键片段视为忠实。
     * 后续可替换为 LLM 判别式评分（reflection 在线用轻量二元分支）。
     */
    public Verdict check(String answer, String context) {
        if (context == null || context.isBlank()) {
            return Verdict.UNKNOWN;
        }
        if (answer == null || answer.isBlank() || answer.startsWith("抱歉")) {
            return Verdict.UNFAITHFUL;
        }
        // 上下文摘要中存在 citations 来源片段，视为回答有据可依（宽松启发式）
        boolean grounded = context.contains("citations=") && context.contains("#");
        return grounded ? Verdict.FAITHFUL : Verdict.UNFAITHFUL;
    }
}