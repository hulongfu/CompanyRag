package com.company.rag.agent.tool;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolRegistryTest {

    private static AgentTool tool(String name) {
        return new AgentTool() {
            public String getName() { return name; }
            public String getDescription() { return "desc"; }
            public Map<String, Object> getParameterSchema() { return Map.of(); }
            public String execute(Map<String, Object> params) { return "ok"; }
        };
    }

    @Test
    void remove_existing_tool_returns_true_and_bumps_version() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of());
        registry.register(tool("a"));
        int before = registry.getVersion();
        assertTrue(registry.remove("a"));
        assertFalse(registry.hasTool("a"));
        assertTrue(registry.getVersion() > before);
    }

    @Test
    void remove_missing_tool_returns_false_and_keeps_version() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of());
        int before = registry.getVersion();
        assertFalse(registry.remove("nope"));
        assertEquals(before, registry.getVersion());
    }

    @Test
    void removeAll_removes_only_present_names_and_returns_count() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("a"), tool("b"), tool("c")));
        int removed = registry.removeAll(List.of("a", "missing", "c"));
        assertEquals(2, removed);
        assertTrue(registry.hasTool("b"));
        assertFalse(registry.hasTool("a"));
    }
}