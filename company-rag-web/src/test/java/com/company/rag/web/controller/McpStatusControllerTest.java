package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.mcp.client.McpClientRegistry;
import com.company.rag.mcp.client.McpEndpointStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpStatusControllerTest {

    @Test
    void status_returns_ok_with_snapshots() {
        McpClientRegistry registry = mock(McpClientRegistry.class);
        when(registry.statusSnapshots()).thenReturn(List.of(
                McpEndpointStatus.builder().clientId("a").connected(false).build()));
        McpStatusController controller = new McpStatusController(registry);

        R<List<McpEndpointStatus>> result = controller.status();

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("a", result.getData().get(0).getClientId());
    }
}