package com.company.rag.rag.eval.answer;

/**
 * 回答相对参考答案的正确性评估。第一版以可观察的规则式判定为主：
 * 回答非空、非兜底且长度达到合理下限视为"给出实质内容"。
 */
public class AnswerCorrectnessEvaluator implements AnswerEvaluator {

    private static final int MIN_ANSWER_LENGTH = 10;

    @Override
    public String dimensionName() {
        return "correctness";
    }

    @Override
    public boolean evaluate(String query, String context, String answer) {
        return answer != null && !answer.isBlank()
                && answer.length() >= MIN_ANSWER_LENGTH && !answer.startsWith("抱歉");
    }
}