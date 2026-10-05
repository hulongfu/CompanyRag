package com.company.rag.rag.eval.answer;

import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 回答相对检索上下文的忠实度判定，供 reflection（在线轻量分支）与本 spec 的
 * AnswerFaithfulnessEvaluator（离线可重评分）复用，避免各写一套。
 *
 * 判定语义：在「确有检索来源（citations= 门槛）」的前提下，进一步校验回答是否
 * 真正扎根于检索正文——以回答的字符二元组在正文中的覆盖比例衡量。这比早期的
 * 「上下文出现 citations= 标记即判忠实」的宽松启发式更能抓出「答非所问/编造」，
 * 且与 AnswerRelevancyEvaluator 采用一致的字符二元组覆盖度量（中文无需分词）。
 */
@Component
public class FaithfulnessChecker {

    /**
     * 回答字符二元组在检索正文中的命中占比下限，达到即视为有据（忠实）。
     * 取值低于 relevancy 的 0.2：忠实只要求回答的关键表述确有所本，不必逐字贴合。
     */
    private static final double GROUNDING_COVERAGE_THRESHOLD = 0.15;

    /**
     * 判定结果：忠实 / 不忠实 / 无法判定（上下文缺失）。
     */
    public enum Verdict { FAITHFUL, UNFAITHFUL, UNKNOWN }

    /**
     * 检查回答是否忠实于检索上下文。
     *
     * @param answer  模型回答
     * @param context 检索上下文（形如 "citations=a.md#0\n[来源:a.md] 正文..."）
     */
    public Verdict check(String answer, String context) {
        if (context == null || context.isBlank()) {
            return Verdict.UNKNOWN;
        }
        if (answer == null || answer.isBlank() || answer.startsWith("抱歉")) {
            return Verdict.UNFAITHFUL;
        }
        // 门槛：上下文必须以 citations= 声明存在检索来源，否则无法判定"有据"
        if (!context.contains("citations=")) {
            return Verdict.UNFAITHFUL;
        }
        // 正文区：剔除首行的 citations= 声明，只比对真实检索正文
        int newline = context.indexOf('\n');
        String body = newline >= 0 ? context.substring(newline + 1) : "";
        if (body.isBlank()) {
            return Verdict.UNFAITHFUL;
        }
        String normAnswer = normalize(answer);
        String normBody = normalize(body);
        Set<String> answerBigrams = toBigrams(normAnswer);
        if (answerBigrams.isEmpty()) {
            return Verdict.UNFAITHFUL;
        }
        // 回答的相邻字符对在检索正文中的命中占比作为"有据"度量
        long matched = answerBigrams.stream().filter(normBody::contains).count();
        return (double) matched / answerBigrams.size() >= GROUNDING_COVERAGE_THRESHOLD
                ? Verdict.FAITHFUL
                : Verdict.UNFAITHFUL;
    }

    /** 归一化：保留字母/数字/汉字，去除标点空白并转小写，消除引用格式等噪声对判定的影响。 */
    private String normalize(String s) {
        return s.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase();
    }

    /** 生成字符二元组；长度不足 2 时返回空集。 */
    private Set<String> toBigrams(CharSequence s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 2 <= s.length(); i++) {
            set.add(s.subSequence(i, i + 2).toString());
        }
        return set;
    }
}