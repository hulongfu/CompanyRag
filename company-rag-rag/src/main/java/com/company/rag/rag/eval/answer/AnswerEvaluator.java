package com.company.rag.rag.eval.answer;

/**
 * 回答质量评估接口。为内部离线/规则式评估提供自包含契约，不依赖 Spring AI
 * {@code org.springframework.ai.evaluation.Evaluator} SPI（该 SPI 面向流式在线评估，
 * 与本场景的解耦目标不符）。各维度实现（相关性/正确性/忠实度）只需实现
 * {@link #evaluate(String, String, String)} 与 {@link #dimensionName()}。
 */
public interface AnswerEvaluator {

    /**
     * 返回该评估器对应的维度名。
     */
    String dimensionName();

    /**
     * 评估单条回答。
     *
     * @param query   用户问题
     * @param context 检索上下文（可为 null，faithfulness 据此判定）
     * @param answer  待评估的回答
     * @return 该维度是否通过
     */
    boolean evaluate(String query, String context, String answer);
}