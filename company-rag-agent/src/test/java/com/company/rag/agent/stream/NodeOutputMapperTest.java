package com.company.rag.agent.stream;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link NodeOutputMapper} 单测：覆盖 spec §4.1 场景表，并锁定两条依赖框架内部行为的判据。
 */
class NodeOutputMapperTest {

    private final NodeOutputMapper mapper = new NodeOutputMapper();

    private static final String MODEL_NODE = RunnableConfig.AGENT_MODEL_NAME + "react";
    private static final String TOOL_NODE = RunnableConfig.AGENT_TOOL_NAME + "searchKnowledgeBase";
    private static final String HOOK_NODE = RunnableConfig.AGENT_HOOK_NAME_PREFIX + "skills";
    private static final String AGENT = "react";

    /**
     * 构造流式帧。chunk 由框架从 message 推导，与生产路径一致，不由测试指定。
     */
    private static StreamingOutput<Message> frame(String node, Message message, OutputType type) {
        return new StreamingOutput<>(message, node, AGENT, new OverAllState(), type);
    }

    /** ToolResponseMessage 构造器是 protected，只能走 builder。 */
    private static ToolResponseMessage toolResponseMessage(ToolResponseMessage.ToolResponse... responses) {
        return ToolResponseMessage.builder().responses(List.of(responses)).build();
    }

    private static AssistantMessage toolCallMessage() {
        return AssistantMessage.builder()
                .content("让我查一下")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function",
                        "searchKnowledgeBase", "{\"query\":\"x\"}")))
                .build();
    }

    @Test
    void map_modelStreamingTextChunk_emitsAnswerDelta() {
        var events = mapper.map(frame(MODEL_NODE, new AssistantMessage("你"),
                OutputType.AGENT_MODEL_STREAMING));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentStreamEventType.ANSWER_DELTA);
        assertThat(events.get(0).text()).isEqualTo("你");
    }

    /**
     * 锁定判据一：工具轮的增量帧 chunk() 为空，因此不会有任何内容流到前端。
     * 框架 extractChunkFromMessage() 若在带 tool calls 时改为返回文本，本用例会红，
     * 提醒同步调整 mapper（届时需要重新引入「思考增量」事件）。
     */
    @Test
    void map_modelStreamingWithToolCalls_emitsNothing() {
        StreamingOutput<Message> toolRoundFrame = frame(MODEL_NODE, toolCallMessage(),
                OutputType.AGENT_MODEL_STREAMING);

        assertThat(toolRoundFrame.chunk()).isNull();
        assertThat(mapper.map(toolRoundFrame)).isEmpty();
    }

    @Test
    void map_blankChunk_emitsNothing() {
        assertThat(mapper.map(frame(MODEL_NODE, new AssistantMessage(""),
                OutputType.AGENT_MODEL_STREAMING))).isEmpty();
    }

    /**
     * 锁定判据二：AGENT_MODEL_FINISHED 的 chunk() 是聚合后的整轮全文，
     * 放行会把已逐帧下发的答案整段重发，故必须忽略。
     */
    @Test
    void map_modelFinished_ignoresWholeRoundText() {
        StreamingOutput<Message> finished = frame(MODEL_NODE, new AssistantMessage("你好，我是助手"),
                OutputType.AGENT_MODEL_FINISHED);

        assertThat(finished.chunk()).isEqualTo("你好，我是助手");
        assertThat(mapper.map(finished)).isEmpty();
    }

    @Test
    void roundFinishedText_returnsWholeRoundTextOnlyOnFinishedFrame() {
        assertThat(mapper.roundFinishedText(
                frame(MODEL_NODE, new AssistantMessage("最终答案全文"), OutputType.AGENT_MODEL_FINISHED)))
                .isEqualTo("最终答案全文");
        // 增量帧上返回 null，调用方据此不会被中途分片误当作完整答案
        assertThat(mapper.roundFinishedText(
                frame(MODEL_NODE, new AssistantMessage("分片"), OutputType.AGENT_MODEL_STREAMING)))
                .isNull();
    }

    @Test
    void map_toolFinished_stripsToolNodePrefixAndKeepsToolName() {
        var events = mapper.map(frame(TOOL_NODE, new AssistantMessage("工具结果"),
                OutputType.AGENT_TOOL_FINISHED));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentStreamEventType.TOOL_END);
        assertThat(events.get(0).toolName()).isEqualTo("searchKnowledgeBase");
        // 无可靠来源时置 null，不编造耗时与状态
        assertThat(events.get(0).durationMs()).isNull();
        assertThat(events.get(0).status()).isNull();
    }

    @Test
    void map_toolFinishedWithoutPrefix_fallsBackToNodeName() {
        var events = mapper.map(frame("someCustomNode", new AssistantMessage("r"),
                OutputType.AGENT_TOOL_FINISHED));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).toolName()).isEqualTo("someCustomNode");
    }

    /**
     * 真机场景：ReactAgent 的工具节点名恒等于 {@code _AGENT_TOOL_}（无工具名后缀），
     * 工具名必须取自 ToolResponseMessage 的响应项，否则会发出空工具名让前端渲染成空白卡片。
     */
    @Test
    void map_toolFinishedWithoutNameInNodeName_usesToolResponseName() {
        var events = mapper.map(frame(RunnableConfig.AGENT_TOOL_NAME,
                toolResponseMessage(new ToolResponseMessage.ToolResponse("call-1", "searchKnowledgeBase", "结果")),
                OutputType.AGENT_TOOL_FINISHED));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentStreamEventType.TOOL_END);
        assertThat(events.get(0).toolName()).isEqualTo("searchKnowledgeBase");
    }

    /**
     * message 优先于 node 名：一轮并行调用多个工具时，一帧展开成多个 TOOL_END 且顺序保持。
     */
    @Test
    void map_toolFinishedWithParallelCalls_emitsOneEventPerTool() {
        var events = mapper.map(frame(TOOL_NODE, toolResponseMessage(
                new ToolResponseMessage.ToolResponse("c1", "searchKnowledgeBase", "a"),
                new ToolResponseMessage.ToolResponse("c2", "queryDatabase", "b")),
                OutputType.AGENT_TOOL_FINISHED));

        assertThat(events).extracting(AgentStreamEvent::toolName)
                .containsExactly("searchKnowledgeBase", "queryDatabase");
    }

    /**
     * 边界：node 名与 message 都给不出工具名时必须整帧跳过，不能把空串当合法工具名下发。
     */
    @Test
    void map_toolFinishedWithUnresolvableToolName_emitsNothing() {
        assertThat(mapper.map(frame(RunnableConfig.AGENT_TOOL_NAME,
                new AssistantMessage("工具结果"), OutputType.AGENT_TOOL_FINISHED))).isEmpty();
    }

    @Test
    void map_modelFinishedWithToolCalls_emitsToolStartPerCall() {
        var events = mapper.map(frame(MODEL_NODE, toolCallMessage(), OutputType.AGENT_MODEL_FINISHED));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(AgentStreamEventType.TOOL_START);
        assertThat(events.get(0).toolName()).isEqualTo("searchKnowledgeBase");
    }

    /**
     * 末轮（产出最终答案、无 tool calls）不得产生任何工具事件，否则前端会出现幽灵工具卡片。
     */
    @Test
    void map_modelFinishedWithoutToolCalls_emitsNothing() {
        assertThat(mapper.map(frame(MODEL_NODE, new AssistantMessage("最终答案"),
                OutputType.AGENT_MODEL_FINISHED))).isEmpty();
    }

    @Test
    void map_hookFrames_neverTreatedAsTool() {
        assertThat(mapper.map(frame(HOOK_NODE, new AssistantMessage("skill"),
                OutputType.AGENT_HOOK_STREAMING))).isEmpty();
        assertThat(mapper.map(frame(HOOK_NODE, new AssistantMessage("skill"),
                OutputType.AGENT_HOOK_FINISHED))).isEmpty();
    }

    @Test
    void map_graphNodeStreamingFrames_ignored() {
        assertThat(mapper.map(frame(MODEL_NODE, new AssistantMessage("x"),
                OutputType.GRAPH_NODE_STREAMING))).isEmpty();
        assertThat(mapper.map(frame(MODEL_NODE, new AssistantMessage("x"),
                OutputType.GRAPH_NODE_FINISHED))).isEmpty();
        assertThat(mapper.map(frame(TOOL_NODE, new AssistantMessage("x"),
                OutputType.AGENT_TOOL_STREAMING))).isEmpty();
    }

    @Test
    void map_bareNodeOutput_ignoredWithoutThrowing() {
        NodeOutput bare = NodeOutput.of("plainNode", AGENT, new OverAllState(), null);

        assertThat(mapper.map(bare)).isEmpty();
        assertThatCode(() -> mapper.map(bare)).doesNotThrowAnyException();
    }

    @Test
    void map_startAndEndFrames_ignored() {
        assertThat(mapper.map(NodeOutput.of("__START__", AGENT, new OverAllState(), null))).isEmpty();
        assertThat(mapper.map(NodeOutput.of("__END__", AGENT, new OverAllState(), null))).isEmpty();
    }

    @Test
    void map_nullInput_ignored() {
        assertThat(mapper.map(null)).isEmpty();
    }
}
