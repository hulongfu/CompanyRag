package com.company.rag.agent.stream;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
            case AGENT_TOOL_FINISHED -> List.of(
                    // NodeOutput 无耗时字段，且 TOOL_START 是否发送未确认，
                    // durationMs / status 无可靠来源时置 null，不编造数值
                    AgentStreamEvent.toolEnd(toolNameOf(output), null, null));
            // AGENT_MODEL_FINISHED 的 message 是聚合后的完整响应，其 chunk() 等于整轮全文，
            // 若放行会把已逐帧下发过的答案再整段重发一次，故必须忽略。
            // AGENT_TOOL_STREAMING 在实测确认会发之前不产出，避免前端出现只有 START 没有 END 的悬挂工具卡片。
            case AGENT_MODEL_FINISHED, AGENT_TOOL_STREAMING -> List.of();
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
     * 节点名判定一律用 {@link RunnableConfig} 常量，不硬编码字面量。
     *
     * <p>{@code node()} 形如 {@code _AGENT_TOOL_<toolName>}；{@code agent()} 是多智能体场景的
     * agent 名而非工具名，故只在 node 不带工具前缀时才退化使用。
     */
    private String toolNameOf(NodeOutput output) {
        String node = output.node();
        if (node != null && node.startsWith(RunnableConfig.AGENT_TOOL_NAME)) {
            return node.substring(RunnableConfig.AGENT_TOOL_NAME.length());
        }
        if (node != null && !node.isBlank()) {
            return node;
        }
        return output.agent();
    }
}
