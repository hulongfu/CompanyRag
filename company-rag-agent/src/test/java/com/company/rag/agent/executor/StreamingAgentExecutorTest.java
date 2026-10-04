package com.company.rag.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.company.rag.agent.service.AgentResult;
import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.context.TenantContextSnapshot;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

class StreamingAgentExecutorTest {

    private ReactAgent reactAgent;
    private ToolCallRecorder recorder;
    private StreamingAgentExecutor executor;

    @BeforeEach
    void setUp() {
        reactAgent = mock(ReactAgent.class);
        recorder = new ToolCallRecorder();
        executor = new StreamingAgentExecutor(reactAgent, recorder,
                new com.company.rag.agent.stream.NodeOutputMapper(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void execute_returnsRealToolContext() throws Exception {
        when(reactAgent.call(eq(List.of(new UserMessage("hi"))), any(RunnableConfig.class)))
                .thenReturn(new AssistantMessage("hello"));

        long start = recorder.recordStart("searchKnowledgeBase", java.util.Map.of("question", "q"));
        recorder.recordEnd("searchKnowledgeBase", start, "success", null, "citations=c1");

        AgentResult result = executor.execute(List.of(new UserMessage("hi")));

        assertEquals("hello", result.getAnswer());
        assertEquals("searchKnowledgeBase:citations=c1", result.getToolContext());
        // 实际调用了 searchKnowledgeBase，ragUsed 应为 true（供在线评估过滤）
        assertEquals(true, result.isRagUsed());
    }

    @Test
    void execute_ragUsedFalse_whenNoSearchCalled() throws Exception {
        when(reactAgent.call(eq(List.of(new UserMessage("hi"))), any(RunnableConfig.class)))
                .thenReturn(new AssistantMessage("hello"));

        // 仅调用其他工具（如 code_search），未调用 searchKnowledgeBase
        long start = recorder.recordStart("code_search", java.util.Map.of("q", "k"));
        recorder.recordEnd("code_search", start, "success", null, "matched=1");

        AgentResult result = executor.execute(List.of(new UserMessage("hi")));

        // 未执行 RAG 检索，ragUsed 应为 false
        assertEquals("hello", result.getAnswer());
        assertEquals(false, result.isRagUsed());
    }

    @Test
    void execute_toolContextEmpty_whenNoToolsCalled() throws Exception {
        when(reactAgent.call(eq(List.of(new UserMessage("hi"))), any(RunnableConfig.class)))
                .thenReturn(new AssistantMessage("hello"));

        AgentResult result = executor.execute(List.of(new UserMessage("hi")));

        assertEquals("", result.getToolContext());
    }

    @Test
    void execute_clearsRecords_afterCapture() throws Exception {
        when(reactAgent.call(eq(List.of(new UserMessage("hi"))), any(RunnableConfig.class)))
                .thenReturn(new AssistantMessage("hello"));

        long start = recorder.recordStart("searchKnowledgeBase", java.util.Map.of("question", "q"));
        recorder.recordEnd("searchKnowledgeBase", start, "success", null, "citations=c1");

        executor.execute(List.of(new UserMessage("hi")));

        // execute 内部在捕获上下文后用 finally 清理工作线程的记录，避免串号/泄漏
        assertEquals("", recorder.captureToolContext());
    }

    @Test
    void execute_carriesToolRecords_toAgentResult() throws Exception {
        when(reactAgent.call(eq(List.of(new UserMessage("hi"))), any(RunnableConfig.class)))
                .thenReturn(new AssistantMessage("hello"));

        long start = recorder.recordStart("searchKnowledgeBase", java.util.Map.of("question", "q"));
        recorder.recordEnd("searchKnowledgeBase", start, "success", null, "citations=c1");

        AgentResult result = executor.execute(List.of(new UserMessage("hi")));

        // 工具明细必须由 executor 工作线程在清理前带出（跨线程 ThreadLocal 不可见），
        // 供 RagAgentService 输出 tools=[...] 结构化日志；漏带即回归旧 bug（恒空）
        assertEquals(1, result.getToolRecords().size());
        assertEquals("searchKnowledgeBase", result.getToolRecords().get(0).getToolName());
    }

    @Test
    void execute_carriesTenantSnapshot_inRunnableConfigMetadata() throws Exception {
        // 工具节点跑在 graph 框架线程上，ThreadLocal 租户上下文跟不过去；
        // 快照必须放进 RunnableConfig metadata，由 ToolCallback 在执行线程写回
        TenantContext.setTenantId(7L);
        TenantContext.setSchema("tenant_x");
        try {
            when(reactAgent.call(eq(List.of(new UserMessage("hi"))), any(RunnableConfig.class)))
                    .thenReturn(new AssistantMessage("hello"));

            executor.execute(List.of(new UserMessage("hi")));

            ArgumentCaptor<RunnableConfig> config = ArgumentCaptor.forClass(RunnableConfig.class);
            org.mockito.Mockito.verify(reactAgent).call(eq(List.of(new UserMessage("hi"))), config.capture());
            Object snapshot = config.getValue()
                    .metadata(TenantContextSnapshot.METADATA_KEY)
                    .orElse(null);
            assertTrue(snapshot instanceof TenantContextSnapshot);
            assertEquals(7L, ((TenantContextSnapshot) snapshot).getTenantId());
            assertEquals("tenant_x", ((TenantContextSnapshot) snapshot).getSchema());
        } finally {
            TenantContext.clear();
        }
    }
}