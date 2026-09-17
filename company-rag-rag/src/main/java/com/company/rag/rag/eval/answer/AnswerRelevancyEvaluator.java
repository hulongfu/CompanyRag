package com.company.rag.rag.eval.answer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 回答相对问题的相关性评估。第一版以可观察的规则式判定为主：
 * 回答非空且包含问题核心词/非"抱歉"兜底，即视为相关。
 */
@Component
@ConditionalOnProperty(name = "rag.eval.enabled", havingValue = "true")
public class AnswerRelevancyEvaluator implements AnswerEvaluator {

    @Override
    public String dimensionName() {
        return "relevancy";
    }

    @Override
    public boolean evaluate(String query, String context, String answer) {
        if (answer == null || answer.isBlank() || answer.startsWith("抱歉")) {
            return false;
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        // 简单包含判定：问题核心词（去空白后的子串）出现在回答中
        String[] tokens = query.split("\\s+");
        if (tokens.length == 0) return true;
        for (String t : tokens) {
            String norm = t.replace("？", "").replace("?", "");
            if (norm.length() >= 2 && answer.contains(norm)) {
                return true;
            }
        }
        return false;
    }
}