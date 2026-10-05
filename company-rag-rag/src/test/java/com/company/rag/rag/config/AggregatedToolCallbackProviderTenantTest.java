package com.company.rag.rag.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.rag.agent.approve.ToolApprovalService;
import com.company.rag.agent.tool.AgentTool;
import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.client.McpClientRegistry;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.context.TenantContextSnapshot;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

/**
 * 验证工具回调能把租户快照写进自己的执行线程，并在结束后精确还原。
 *
 * <p>回归背景：graph 工具节点跑在框架调度线程上，ThreadLocal 租户上下文跟不过去，
 * 导致知识库检索报「未设置租户上下文」、审计落库缺 tenant_id。
 */
class AggregatedToolCallbackProviderTenantTest {

    private AgentToolRegistry registry;
    private ToolApprovalService approvalService;
    private AggregatedToolCallbackProvider provider;

    /** 记录工具执行瞬间看到的租户上下文 */
    private final AtomicReference<Long> tenantIdInsideTool = new AtomicReference<>();
    private final AtomicReference<String> schemaInsideTool = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        AgentTool stubTool = new AgentTool() {
            @Override
            public String getName() {
                return "stubTool";
            }

            @Override
            public String getDescription() {
                return "stub";
            }

            @Override
            public Map<String, Object> getParameterSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }

            @Override
            public String execute(Map<String, Object> params) {
                tenantIdInsideTool.set(TenantContext.getTenantId());
                schemaInsideTool.set(TenantContext.getSchema());
                return "ok";
            }
        };

        registry = new AgentToolRegistry(List.of(stubTool));
        approvalService = mock(ToolApprovalService.class);
        when(approvalService.needsApproval(anyString(), any())).thenReturn(false);
        provider = new AggregatedToolCallbackProvider(registry, mock(McpClientRegistry.class), approvalService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ToolCallback stubCallback() {
        for (ToolCallback callback : provider.getToolCallbacks()) {
            if ("stubTool".equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        throw new IllegalStateException("stubTool 未生成 ToolCallback");
    }

    @Test
    void call_restoresTenantContextOnWorkerThread() throws Exception {
        TenantContext.setTenantId(7L);
        TenantContext.setSchema("tenant_concurrent_test_3");
        TenantContextSnapshot snapshot = TenantContextSnapshot.captureNow();
        TenantContext.clear();

        // 模拟 graph 工具线程：与请求线程不同，ThreadLocal 里没有任何租户信息
        Thread worker = new Thread(() -> stubCallback().call("{}",
                new ToolContext(Map.of(TenantContextSnapshot.METADATA_KEY, snapshot))));
        worker.start();
        worker.join();

        assertThat(tenantIdInsideTool.get()).isEqualTo(7L);
        assertThat(schemaInsideTool.get()).isEqualTo("tenant_concurrent_test_3");
        // 工具线程属于线程池，退出后不能残留租户，否则复用时会串到别的租户
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void call_restoresCallerContext_afterSameThreadToolExecution() {
        TenantContext.setTenantId(9L);
        TenantContext.setSchema("tenant_caller");
        TenantContextSnapshot snapshot = TenantContextSnapshot.of(7L, "tenant_tool");

        stubCallback().call("{}", new ToolContext(Map.of(TenantContextSnapshot.METADATA_KEY, snapshot)));

        assertThat(tenantIdInsideTool.get()).isEqualTo(7L);
        // 同线程执行时不能把调用方自己的租户清掉，否则调用方后续 DB 访问会变成无租户状态
        assertThat(TenantContext.getTenantId()).isEqualTo(9L);
        assertThat(TenantContext.getSchema()).isEqualTo("tenant_caller");
    }

    @Test
    void call_keepsCurrentContext_whenToolContextHasNoSnapshot() {
        TenantContext.setTenantId(9L);

        String result = stubCallback().call("{}", new ToolContext(Map.of()));

        assertThat(result).isEqualTo("ok");
        assertThat(tenantIdInsideTool.get()).isEqualTo(9L);
        assertThat(TenantContext.getTenantId()).isEqualTo(9L);
    }

    @Test
    void legacyCallWithoutToolContext_stillExecutesTool() {
        TenantContext.setTenantId(9L);

        assertThat(stubCallback().call("{}")).isEqualTo("ok");
        assertThat(tenantIdInsideTool.get()).isEqualTo(9L);
    }
}
