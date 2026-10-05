package com.company.rag.rag.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.node.AgentLlmNode;
import com.alibaba.cloud.ai.graph.agent.node.AgentToolNode;
import com.company.rag.common.event.McpToolRegistryChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.List;

/**
 * MCP 工具注册表变更监听器：把最新工具集补偿注入 ReactAgent 的模型/工具节点。
 * <p>
 * Spring AI Alibaba 的 ReactAgent 在 Bean 构建时对工具列表做「值快照」冻结
 * （DefaultBuilder.gatherLocalTools 用 new ArrayList + List.of(callback[]) 拷贝），
 * 导致运行时（探活重连后）动态注册进 AgentToolRegistry 的 MCP 工具对模型不可见。
 * 字节码证实两节点每次执行都会实时读取自身 toolCallbacks 字段，因此只需在工具集
 * 变更时刷新注入即可让新工具对模型可见，无需重建 ReactAgent。
 * <p>
 * 实现要点：
 * 1. 通过反射读取 ReactAgent 的 private final llmNode/toolNode（各缓存一次），
 *    再调用节点 public setToolCallbacks 注入；反射只在组件初始化时进行一次。
 * 2. 传入节点的是不可变快照（List.copyOf），避免外部对共享列表的并发误改。
 * 3. 注入/刷新异常全部吞掉并告警，保证不影响 MCP 主链路。
 */
@Slf4j
@Component
public class ReactAgentToolRefresher {

    private final ReactAgent reactAgent;
    private final ToolCallbackProvider toolCallbackProvider;

    private final AgentLlmNode llmNode;
    private final AgentToolNode toolNode;

    public ReactAgentToolRefresher(ReactAgent reactAgent,
                                   ToolCallbackProvider toolCallbackProvider) {
        this.reactAgent = reactAgent;
        this.toolCallbackProvider = toolCallbackProvider;
        this.llmNode = extractPrivateField(reactAgent, "llmNode", AgentLlmNode.class);
        this.toolNode = extractPrivateField(reactAgent, "toolNode", AgentToolNode.class);
    }

    @EventListener
    public void onMcpToolRegistryChanged(McpToolRegistryChangedEvent event) {
        log.info("收到 MCP 工具表变更事件，clientId={}，刷新 ReactAgent 工具列表", event.getClientId());
        refreshFromCurrentRegistry();
    }

    /**
     * 从工具回调提供者拉取最新工具集，注入 ReactAgent 的两个节点。
     * <p>
     * 仅更新节点 toolCallbacks 字段还不够：AgentLlmNode 每次调用模型时，工具来源是
     * Bean 构建时固化的 private chatOptions（ToolCallingChatOptions）里的 toolCallbacks，
     * filterToolCallbacks 以它与节点 toolCallbacks 求交集后才会推给模型。因此还需调用
     * chatOptions.setToolCallbacks 刷新该固化字段，否则动态新增的 MCP 工具永远被交集
     * 逻辑过滤掉、模型看不到。
     */
    public void refreshFromCurrentRegistry() {
        try {
            ToolCallback[] callbacks = toolCallbackProvider.getToolCallbacks();
            List<ToolCallback> snapshot = List.of(callbacks);
            if (llmNode != null) {
                llmNode.setToolCallbacks(snapshot);
                refreshLlmNodeChatOptions(snapshot);
            }
            if (toolNode != null) {
                toolNode.setToolCallbacks(snapshot);
            }
            log.info("已刷新 ReactAgent 工具列表，共 {} 个工具", snapshot.size());
        } catch (Exception e) {
            // 刷新失败不应影响 MCP 主链路，仅记录告警
            log.warn("刷新 ReactAgent 工具列表失败", e);
        }
    }

    /**
     * 通过反射读取 llmNode 的 private chatOptions 字段并调用其 setToolCallbacks，
     * 使每次 LLM 调用的工具来源（filterToolCallbacks 的 options.getToolCallbacks()）
     * 携带最新工具集。取不到或类型不符时降级跳过，不影响主链路。
     */
    private void refreshLlmNodeChatOptions(List<ToolCallback> snapshot) {
        try {
            Field chatOptionsField = AgentLlmNode.class.getDeclaredField("chatOptions");
            chatOptionsField.setAccessible(true);
            Object chatOptions = chatOptionsField.get(llmNode);
            if (chatOptions instanceof ToolCallingChatOptions toolCallingOptions) {
                toolCallingOptions.setToolCallbacks(snapshot);
                log.info("已刷新 ReactAgent llmNode.chatOptions 工具列表，共 {} 个工具", snapshot.size());
            } else {
                log.warn("llmNode.chatOptions 类型异常：{}，跳过 chatOptions 刷新", chatOptions);
            }
        } catch (Exception e) {
            // 字段名随版本可能变化，刷新失败仅告警，不阻断主链路
            log.warn("刷新 ReactAgent llmNode.chatOptions 失败", e);
        }
    }

    /**
     * 反射读取 ReactAgent 的 private final 节点字段；取不到时返回 null 并告警（不阻断）。
     */
    private static <T> T extractPrivateField(Object target, String fieldName, Class<T> fieldType) {
        try {
            Field field = ReactAgent.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return fieldType.cast(field.get(target));
        } catch (Exception e) {
            // spring-ai-alibaba 升级后字段名可能变化，取不到时按降级处理，不阻断主链路
            log.warn("读取 ReactAgent 字段 {}：{}（刷新将降级跳过此节点）", fieldName, e);
            return null;
        }
    }
}