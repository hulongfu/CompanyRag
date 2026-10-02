package com.company.rag.agent.stream;

import com.company.rag.agent.service.AgentResult;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Agent 流式事件（SSE 帧的序列化载体）。
 *
 * <p>{@code result} 字段标记 {@link JsonIgnore}：轨迹数据只供服务端落库与评估使用，
 * 不外泄给前端，避免与 {@code rag_session} 落库内容形成两条可能不一致的出口。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentStreamEvent(
        AgentStreamEventType type,
        /** 增量文本；非文本事件为 null */
        String text,
        /** 仅 TOOL_START / TOOL_END */
        String toolName,
        /** 仅 TOOL_END；无法可靠测量时为 null，不编造数值 */
        Long durationMs,
        /** 仅 TOOL_END；无法可靠判定时为 null */
        String status,
        /** 仅 DONE：answer / toolContext / ragUsed，不参与序列化 */
        @JsonIgnore AgentResult result
) {

    public static AgentStreamEvent answerDelta(String text) {
        return new AgentStreamEvent(AgentStreamEventType.ANSWER_DELTA, text, null, null, null, null);
    }

    public static AgentStreamEvent toolStart(String toolName) {
        return new AgentStreamEvent(AgentStreamEventType.TOOL_START, null, toolName, null, null, null);
    }

    public static AgentStreamEvent toolEnd(String toolName, Long durationMs, String status) {
        return new AgentStreamEvent(AgentStreamEventType.TOOL_END, null, toolName, durationMs, status, null);
    }

    public static AgentStreamEvent done(AgentResult result) {
        return new AgentStreamEvent(AgentStreamEventType.DONE, null, null, null, null, result);
    }

    public static AgentStreamEvent error(String message) {
        return new AgentStreamEvent(AgentStreamEventType.ERROR, message, null, null, null, null);
    }
}
