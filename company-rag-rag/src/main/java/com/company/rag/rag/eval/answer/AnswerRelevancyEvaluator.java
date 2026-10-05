package com.company.rag.rag.eval.answer;

import java.util.HashSet;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 回答相对问题的相关性评估。第一版以可观察的规则式判定为主：
 * 回答非空且与问题在词汇上有实质重叠，即视为相关。
 *
 * 说明：中文（无空格）查询如果用 {@code split("\\s+")} 会退化为单个整句 token，
 * 无法用「回答包含整句」判定相关性。因此改用字符二元组覆盖度量——
 * 回答命中查询中足够比例的相邻字符对，说明其承接了问题的核心表达。
 */
@Component
@ConditionalOnProperty(name = "rag.eval.enabled", havingValue = "true")
public class AnswerRelevancyEvaluator implements AnswerEvaluator {

    /** 查询字符二元组在回答中的命中占比下限，达到即视为相关 */
    private static final double COVERAGE_THRESHOLD = 0.2;

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
        String normQuery = normalize(query);
        String normAnswer = normalize(answer);
        Set<String> queryBigrams = toBigrams(normQuery);
        if (queryBigrams.isEmpty()) {
            return true;
        }
        long matched = queryBigrams.stream().filter(normAnswer::contains).count();
        return (double) matched / queryBigrams.size() >= COVERAGE_THRESHOLD;
    }

    private String normalize(String s) {
        return s.trim().toLowerCase()
                .replace("？", "")
                .replace("?", "")
                .replace("，", "")
                .replace(",", "");
    }

    private Set<String> toBigrams(CharSequence s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 2 <= s.length(); i++) {
            set.add(s.subSequence(i, i + 2).toString());
        }
        return set;
    }
}