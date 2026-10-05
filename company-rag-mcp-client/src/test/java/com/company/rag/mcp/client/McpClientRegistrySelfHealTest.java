package com.company.rag.mcp.client;

import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.common.event.McpToolRegistryChangedEvent;
import com.company.rag.mcp.model.McpToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpClientRegistrySelfHealTest {

    /** 可控假客户端：可设置远端工具列表与可达性 */
    static class FakeMcpClient implements McpClient {
        private final String id;
        private List<McpToolDefinition> remoteTools;
        FakeMcpClient(String id, List<McpToolDefinition> remoteTools) {
            this.id = id;
            this.remoteTools = remoteTools;
        }
        public String getClientId() { return id; }
        public void connect() {}
        public void disconnect() {}
        public boolean isConnected() { return true; }
        public List<McpToolDefinition> listTools() { return remoteTools; }
        public Object callTool(String n, Map<String, Object> p) { return null; }
        public boolean ping() { return true; }
        public List<McpToolDefinition> listToolsRemote() { return remoteTools; }
        void setRemoteTools(List<McpToolDefinition> t) { this.remoteTools = t; }
    }

    private static McpToolDefinition toolDef(String name) {
        return new McpToolDefinition(name, "d", Map.of(), null);
    }

    @Test
    void syncTools_replaces_whole_source_tool_set() {
        AgentToolRegistry ar = new AgentToolRegistry(List.of());
        McpClientRegistry registry = new McpClientRegistry(ar);
        FakeMcpClient client = new FakeMcpClient("a", List.of(toolDef("foo"), toolDef("bar")));
        registry.registerClient("a", client);
        assertTrue(ar.hasTool("a_foo"));

        // 模拟远端下架 foo、新增 baz
        client.setRemoteTools(List.of(toolDef("bar"), toolDef("baz")));
        registry.syncTools("a");

        assertTrue(ar.hasTool("a_bar"));
        assertTrue(ar.hasTool("a_baz"));
        assertFalse(ar.hasTool("a_foo"), "已下架工具应被移除");
    }

    @Test
    void removeToolsFor_removes_all_tools_of_source() {
        AgentToolRegistry ar = new AgentToolRegistry(List.of());
        McpClientRegistry registry = new McpClientRegistry(ar);
        FakeMcpClient client = new FakeMcpClient("a", List.of(toolDef("x")));
        registry.registerClient("a", client);
        assertTrue(ar.hasTool("a_x"));

        registry.removeToolsFor("a");

        assertFalse(ar.hasTool("a_x"));
        assertNull(registry.getClient("a"), "按源移除后应释放 client 引用");
        assertTrue(registry.getFailedClients().contains("a"), "移除后应登记为失败");
    }

    @Test
    void tool_change_publishes_registry_changed_event() {
        AgentToolRegistry ar = new AgentToolRegistry(List.of());
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        McpClientRegistry registry = new McpClientRegistry(ar, publisher);
        FakeMcpClient client = new FakeMcpClient("a", List.of(toolDef("foo")));

        registry.registerClient("a", client);
        verify(publisher).publishEvent(any(McpToolRegistryChangedEvent.class));

        reset(publisher);
        registry.syncTools("a");
        verify(publisher).publishEvent(any(McpToolRegistryChangedEvent.class));

        reset(publisher);
        registry.removeToolsFor("a");
        verify(publisher).publishEvent(any(McpToolRegistryChangedEvent.class));
    }

    @Test
    void tool_change_without_publisher_does_not_fail() {
        // 回归：向后兼容构造（无事件发布器）下，工具变更不应抛异常
        AgentToolRegistry ar = new AgentToolRegistry(List.of());
        McpClientRegistry registry = new McpClientRegistry(ar);
        FakeMcpClient client = new FakeMcpClient("a", List.of(toolDef("foo")));
        assertDoesNotThrow(() -> registry.registerClient("a", client));
        assertDoesNotThrow(() -> registry.syncTools("a"));
        assertDoesNotThrow(() -> registry.removeToolsFor("a"));
    }
}