package com.company.rag.mcp.client;

import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.common.service.AuditLogService;
import com.company.rag.mcp.model.McpToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class McpFailureHandlerTest {

    /** 可控假客户端：可设置可达性与远端工具列表 */
    static class FakeMcpClient implements McpClient {
        final String id;
        boolean reachable;
        List<McpToolDefinition> tools;

        FakeMcpClient(String id) {
            this.id = id;
            this.reachable = true;
            this.tools = List.of(new McpToolDefinition("t", "d", Map.of(), null));
        }

        public String getClientId() { return id; }
        public void connect() {}
        public void disconnect() {}
        public boolean isConnected() { return true; }
        public List<McpToolDefinition> listTools() { return tools; }
        public Object callTool(String n, Map<String, Object> p) { return null; }
        public boolean ping() { return reachable; }
        public List<McpToolDefinition> listToolsRemote() { return tools; }
    }

    private McpFailureHandler newHandler(McpClientRegistry reg) {
        AuditLogService audit = mock(AuditLogService.class);
        return new McpFailureHandler(reg, audit);
    }

    @Test
    void param_error_is_ignored() {
        AgentToolRegistry ar = new AgentToolRegistry(List.of());
        McpClientRegistry registry = new McpClientRegistry(ar);
        registry.registerClient("a", new FakeMcpClient("a"));
        McpFailureHandler h = newHandler(registry);

        h.handle("a", new McpToolException(-32602, "invalid params"));

        assertTrue(ar.hasTool("a_t"), "参数错误不应移除任何工具");
        assertNotNull(registry.getClient("a"), "参数错误不应触发移除");
    }

    @Test
    void unreachable_source_has_all_tools_removed() {
        AgentToolRegistry ar = new AgentToolRegistry(List.of());
        McpClientRegistry registry = new McpClientRegistry(ar);
        FakeMcpClient client = new FakeMcpClient("a");
        registry.registerClient("a", client);
        McpFailureHandler h = newHandler(registry);

        client.reachable = false;
        h.handle("a", new McpToolException(-32000, "server internal error"));

        assertFalse(ar.hasTool("a_t"), "远端不可达应移除该源全部工具");
        assertNull(registry.getClient("a"), "远端不可达应释放 client 引用");
    }

    @Test
    void reachable_source_is_synced() {
        AgentToolRegistry ar = new AgentToolRegistry(List.of());
        McpClientRegistry registry = new McpClientRegistry(ar);
        FakeMcpClient client = new FakeMcpClient("a");
        registry.registerClient("a", client);
        McpFailureHandler h = newHandler(registry);

        h.handle("a", new McpToolException(-32000, "server says tool gone"));

        assertTrue(ar.hasTool("a_t"), "远端可达时应保留工具集（同步而非整机移除）");
    }
}