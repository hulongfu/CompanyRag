package com.company.rag.agent.stream;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 graph 层的 {@link NodeOutput} 翻译成项目自有的 {@link AgentStreamEvent}。
 *
 * <p>本类是**全项目唯一**允许 import {@code NodeOutput} / {@code StreamingOutput} /
 * {@code OutputType} 的文件，graph 框架的类型不得外泄到其他任何类；将来更换底层框架只改这里。
 *
 * <p><b>必须保持无状态</b>：本类是单例 Bean，跨事件累积（答案累加）一旦放在实例字段上
 * 就会在并发请求间串号。所有跨帧状态由调用方（{@code StreamingAgentExecutor} 的池任务）
 * 用方法内局部变量承载，本类只做「单帧 → 事件列表」的纯翻译。
 *
 * <p>不抛异常：无法识别的帧只记 warn 并返回空列表，一条脏帧不能打断整条流。
 */
@Slf4j
@Component
public class NodeOutputMapper {

    /**
     * 翻译单个流式帧。返回列表而非单值，因单帧将来可能展开成多个事件。
     */
    public List<AgentStreamEvent> map(NodeOutput output) {
        // 裸 NodeOutput 是 GRAPH_NODE_* 生命周期帧，无文本内容
        if (!(output instanceof StreamingOutput<?> so)) {
            return List.of();
        }
        if (output.isSTART() || output.isEND()) {
            return List.of();
        }

        OutputType type = so.getOutputType();
        if (type == null) {
            log.warn("[STREAM] 忽略无 OutputType 的帧：node={}", output.node());
            return List.of();
        }

        return switch (type) {
            case AGENT_MODEL_STREAMING -> mapModelStreaming(so);
            // 工具轮的开始：框架不发独立的「工具开始」帧，但模型轮的聚合帧 message 里带
            // toolCalls，据此补发 TOOL_START，前端才能显示「执行中 → 已完成」并算出耗时。
            // 该轮的答案文本不在此下发（chunk() 为整轮全文，会重复），只取工具名。
            case AGENT_MODEL_FINISHED -> toolStartsOf(output);
            case AGENT_TOOL_FINISHED -> toolEndsOf(output);
            case AGENT_TOOL_STREAMING -> List.of();
            // 技能钩子节点不是工具调用，绝不可误判为工具（否则 SkillsAgentHook 会被显示成工具）
            case AGENT_HOOK_STREAMING, AGENT_HOOK_FINISHED, GRAPH_NODE_STREAMING, GRAPH_NODE_FINISHED -> List.of();
            default -> {
                log.warn("[STREAM] 忽略未知 OutputType：type={}, node={}", type, output.node());
                yield List.of();
            }
        };
    }

    /**
     * 本轮模型输出的完整文本（聚合后），仅在 {@code AGENT_MODEL_FINISHED} 帧上非空。
     *
     * <p>调用方用它、而非累加增量帧来得到落库答案：末轮 {@code AGENT_MODEL_FINISHED} 的
     * {@code message()} 就是框架自己认定的完整回复，语义与阻塞链路
     * {@code reactAgent.call()} 的返回值一致，且不受增量分片内容影响。
     *
     * @return 非 {@code AGENT_MODEL_FINISHED} 帧返回 {@code null}
     */
    public String roundFinishedText(NodeOutput output) {
        if (output instanceof StreamingOutput<?> so
                && so.getOutputType() == OutputType.AGENT_MODEL_FINISHED
                && so.message() != null) {
            return so.message().getText();
        }
        return null;
    }

    /**
     * 模型增量帧 → 答案增量。
     *
     * <p>不需要在此区分「思考」与「答案」：graph 框架的
     * {@code StreamingOutput.extractChunkFromMessage()} 在当轮 message 带 tool calls 时
     * 返回 {@code null}，即工具轮的增量帧 {@code chunk()} 恒为空，天然不会污染答案。
     * 该事实由 {@code NodeOutputMapperTest} 的断言锁定，框架升级改变它会立刻红。
     */
    private List<AgentStreamEvent> mapModelStreaming(StreamingOutput<?> so) {
        String chunk = so.chunk();
        if (chunk == null || chunk.isEmpty()) {
            return List.of();
        }
        return List.of(AgentStreamEvent.answerDelta(chunk));
    }

    /**
     * 模型轮聚合帧 → 该轮发起的工具调用（TOOL_START）。
     *
     * <p>框架不发独立的「工具开始」帧，只发 {@code AGENT_TOOL_FINISHED}。若只翻译结束帧，
     * 前端工具卡片会凭空出现、且无法计算耗时，故从模型轮的 {@link AssistantMessage#hasToolCalls()}
     * 补发开始事件。无工具调用的模型轮（即产出最终答案的末轮）返回空列表。
     */
    private List<AgentStreamEvent> toolStartsOf(NodeOutput output) {
        if (output instanceof StreamingOutput<?> so && so.message() instanceof AssistantMessage am && am.hasToolCalls()) {
            List<AgentStreamEvent> events = new ArrayList<>(am.getToolCalls().size());
            for (AssistantMessage.ToolCall toolCall : am.getToolCalls()) {
                String name = toolCall.name();
                if (name != null && !name.isBlank()) {
                    events.add(AgentStreamEvent.toolStart(name));
                }
            }
            return events;
        }
        return List.of();
    }

    /**
     * 工具节点聚合帧 → 工具执行结束（TOOL_END）。
     *
     * <p>工具名取自 {@link ToolResponseMessage#getResponses()} 的 {@code name()}：
     * ReactAgent 的工具节点名恒等于 {@link RunnableConfig#AGENT_TOOL_NAME} 常量本身、
     * 不带工具名后缀，从 node 名截取只会得到空串（真机实测），因此 node 名仅在 message
     * 不携带响应时作退化来源。一轮并行调用多个工具时，本帧会展开成多个 TOOL_END。
     *
     * <p>{@code durationMs} 由调用方按 TOOL_START/TOOL_END 的实际时间差补全（跨帧状态
     * 不允许放在本类实例字段上）；{@code status} 无可靠来源，置 null 不编造。
     */
    private List<AgentStreamEvent> toolEndsOf(NodeOutput output) {
        if (output instanceof StreamingOutput<?> so && so.message() instanceof ToolResponseMessage trm) {
            List<AgentStreamEvent> events = new ArrayList<>(trm.getResponses().size());
            for (ToolResponseMessage.ToolResponse response : trm.getResponses()) {
                String name = response.name();
                if (name != null && !name.isBlank()) {
                    events.add(AgentStreamEvent.toolEnd(name, null, null));
                }
            }
            if (!events.isEmpty()) {
                return events;
            }
        }
        String fallback = toolNameOf(output);
        return fallback == null || fallback.isBlank()
                ? List.of()
                : List.of(AgentStreamEvent.toolEnd(fallback, null, null));
    }

    /**
     * 退化路径的工具名：节点名带工具前缀时截取后缀，否则用节点名本身。
     *
     * <p>节点名恰好等于 {@link RunnableConfig#AGENT_TOOL_NAME}（无后缀）时返回 {@code null}，
     * 交由调用方跳过该帧，不能把空串当成合法工具名发给前端。
     */
    private String toolNameOf(NodeOutput output) {
        String node = output.node();
        if (node != null && node.startsWith(RunnableConfig.AGENT_TOOL_NAME)) {
            String suffix = node.substring(RunnableConfig.AGENT_TOOL_NAME.length());
            return suffix.isBlank() ? null : suffix;
        }
        if (node != null && !node.isBlank()) {
            return node;
        }
        return output.agent();
    }
}
