package com.company.rag.agent.stream;

/**
 * Agent 流式事件类型。
 *
 * <p>事件序列约定：若干 {@link #ANSWER_DELTA} / {@link #TOOL_START} / {@link #TOOL_END}
 * 之后，以唯一的 {@link #DONE} 正常收尾；异常路径只发一个 {@link #ERROR} 且**不发**
 * {@link #DONE}（半截答案不得入库）。
 *
 * <p>没有「思考增量」事件：graph 框架在当轮模型输出带 tool calls 时
 * {@code StreamingOutput.chunk()} 恒为 {@code null}，工具轮的文本天然不会流到前端。
 */
public enum AgentStreamEventType {

    /** 工具开始执行 */
    TOOL_START,

    /** 工具执行结束 */
    TOOL_END,

    /** 答案增量：仅最终轮模型的文本增量 */
    ANSWER_DELTA,

    /** 流正常结束，携带落库用的 AgentResult */
    DONE,

    /** 流内失败，携带可读错误信息；此后不再有 DONE */
    ERROR
}
