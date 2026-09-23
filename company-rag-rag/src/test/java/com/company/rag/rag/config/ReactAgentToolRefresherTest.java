package com.company.rag.rag.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.company.rag.common.event.McpToolRegistryChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReactAgentToolRefresherTest {

    /** 从节点字段反射读取当前工具回调列表，验证注入结果 */
    private static List<?> nodeToolCallbacks(Object reactAgent, String fieldName) throws Exception {
        Field nodeField = ReactAgent.class.getDeclaredField(fieldName);
        nodeField.setAccessible(true);
        Object node = nodeField.get(reactAgent);
        Field cbField = node.getClass().getDeclaredField("toolCallbacks");
        cbField.setAccessible(true);
        return (List<?>) cbField.get(node);
    }

    private static ToolCallback callback(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        when(cb.getToolDefinition()).thenReturn(
                ToolDefinition.builder().name(name).description("desc").inputSchema("{}").build());
        return cb;
    }

    /** 从 llmNode 的 private chatOptions 读取工具回调列表，验证固化工具集也已刷新 */
    private static List<?> llmNodeChatOptionsTools(Object reactAgent) throws Exception {
        Field nodeField = ReactAgent.class.getDeclaredField("llmNode");
        nodeField.setAccessible(true);
        Object node = nodeField.get(reactAgent);
        Field chatOptionsField = node.getClass().getDeclaredField("chatOptions");
        chatOptionsField.setAccessible(true);
        Object chatOptions = chatOptionsField.get(node);
        return ((org.springframework.ai.model.tool.ToolCallingChatOptions) chatOptions).getToolCallbacks();
    }

    @Test
    void refresh_injects_current_tools_into_nodes() throws Exception {
        // 构造一个可用的 ReactAgent：注入 mock ChatModel + mock provider + 一个初始工具
        ToolCallbackProviderStub provider = new ToolCallbackProviderStub(callback("base"));
        ChatModel chatModel = mock(ChatModel.class);

        ReactAgent reactAgent = ReactAgent.builder()
                .name("rag-agent")
                .model(chatModel)
                .toolCallbackProviders(provider)
                .build();

        ReactAgentToolRefresher refresher = new ReactAgentToolRefresher(reactAgent, provider);

        // 动态新增工具后刷新
        provider.setCallbacks(callback("base"), callback("custom_read_file"), callback("custom_read_pdf"));
        refresher.refreshFromCurrentRegistry();

        List<?> llmTools = nodeToolCallbacks(reactAgent, "llmNode");
        List<?> toolTools = nodeToolCallbacks(reactAgent, "toolNode");
        List<?> llmChatOptionsTools = llmNodeChatOptionsTools(reactAgent);
        assertNotNull(llmTools);
        assertNotNull(toolTools);
        assertNotNull(llmChatOptionsTools);
        assertEquals(3, llmTools.size(), "刷新后 llmNode 应包含新注入的工具");
        assertEquals(3, toolTools.size(), "刷新后 toolNode 应包含新注入的工具");
        assertEquals(3, llmChatOptionsTools.size(), "刷新后 llmNode.chatOptions 固化工具集应同步更新");
    }

    @Test
    void event_trigger_refreshes_tools() throws Exception {
        ToolCallbackProviderStub provider = new ToolCallbackProviderStub(callback("base"));
        ChatModel chatModel = mock(ChatModel.class);
        ReactAgent reactAgent = ReactAgent.builder()
                .name("rag-agent")
                .model(chatModel)
                .toolCallbackProviders(provider)
                .build();
        ReactAgentToolRefresher refresher = new ReactAgentToolRefresher(reactAgent, provider);

        provider.setCallbacks(callback("base"), callback("custom_read_word"));
        refresher.onMcpToolRegistryChanged(new McpToolRegistryChangedEvent(this, "custom"));

        assertEquals(2, nodeToolCallbacks(reactAgent, "llmNode").size());
        assertEquals(2, llmNodeChatOptionsTools(reactAgent).size());
    }

    /** 简易 provider：固定返回当前回调数组，模拟实时工具源 */
    static class ToolCallbackProviderStub implements org.springframework.ai.tool.ToolCallbackProvider {
        private ToolCallback[] callbacks;
        ToolCallbackProviderStub(ToolCallback... callbacks) { this.callbacks = callbacks; }
        void setCallbacks(ToolCallback... c) { this.callbacks = c; }
        @Override
        public ToolCallback[] getToolCallbacks() { return callbacks; }
    }
}