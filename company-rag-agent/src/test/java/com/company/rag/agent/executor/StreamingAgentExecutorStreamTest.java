package com.company.rag.agent.executor;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.company.rag.agent.stream.AgentStreamEvent;
import com.company.rag.agent.stream.AgentStreamEventType;
import com.company.rag.agent.stream.NodeOutputMapper;
import com.company.rag.agent.stream.TenantStreamContext;
import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.tenant.context.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StreamingAgentExecutor#executeStream} 单测。
 *
 * <p>用同步执行器（{@code Runnable::run}）让池任务在测试线程内跑完，事件先缓冲在 unicast sink
 * 里，订阅时一次性取出，从而可用普通断言校验事件序列，不必与真实线程竞态。
 */
class StreamingAgentExecutorStreamTest {

    private static final String MODEL_NODE = RunnableConfig.AGENT_MODEL_NAME + "react";
    private static final String TOOL_NODE = RunnableConfig.AGENT_TOOL_NAME + "searchKnowledgeBase";
    private static final String AGENT = "react";
    private static final String SESSION_ID = "session-1";

    private ReactAgent reactAgent;
    private CompiledGraph compiledGraph;
    private ToolCallRecorder recorder;
    private SimpleMeterRegistry meterRegistry;
    private StreamingAgentExecutor executor;

    private final Executor directExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        reactAgent = mock(ReactAgent.class);
        compiledGraph = mock(CompiledGraph.class);
        recorder = mock(ToolCallRecorder.class);
        meterRegistry = new SimpleMeterRegistry();
        executor = new StreamingAgentExecutor(reactAgent, recorder, new NodeOutputMapper(), meterRegistry);
        when(reactAgent.getCompiledGraph()).thenReturn(compiledGraph);
        when(recorder.captureToolContext()).thenReturn("ctx");
        when(recorder.usedTool(anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static StreamingOutput<Message> frame(String node, Message message, OutputType type) {
        return new StreamingOutput<>(message, node, AGENT, new OverAllState(), type);
    }

    private static StreamingOutput<Message> modelChunk(String text) {
        return frame(MODEL_NODE, new AssistantMessage(text), OutputType.AGENT_MODEL_STREAMING);
    }

    private static StreamingOutput<Message> toolFinished() {
        return frame(TOOL_NODE, new AssistantMessage(""), OutputType.AGENT_TOOL_FINISHED);
    }

    private void givenGraphStream(Flux<NodeOutput> stream) {
        when(compiledGraph.stream(anyMap(), any(RunnableConfig.class))).thenReturn(stream);
    }

    private List<AgentStreamEvent> collect(TenantStreamContext ctx, AtomicBoolean cancelled) {
        return executor.executeStream(List.of(new UserMessage("hi")), SESSION_ID, cancelled, ctx,
                directExecutor, 60, 5).collectList().block();
    }

    private TenantStreamContext contextOf(String schema) {
        TenantContext.setSchema(schema);
        TenantContext.setTenantId(7L);
        return TenantStreamContext.captureNow();
    }

    @Test
    void executeStream_normalFlow_emitsToolAndAnswerEventsInOrder() {
        givenGraphStream(Flux.just(toolFinished(), modelChunk("你"), modelChunk("好")));

        List<AgentStreamEvent> events = collect(contextOf("tenant_a"), new AtomicBoolean(false));

        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly(AgentStreamEventType.TOOL_END,
                        AgentStreamEventType.ANSWER_DELTA,
                        AgentStreamEventType.ANSWER_DELTA,
                        AgentStreamEventType.DONE);
    }

    @Test
    void executeStream_success_doneAnswerIsConcatenatedAnswerDeltas() {
        givenGraphStream(Flux.just(toolFinished(), modelChunk("公司"), modelChunk("制度")));

        List<AgentStreamEvent> events = collect(contextOf("tenant_a"), new AtomicBoolean(false));

        AgentStreamEvent done = events.get(events.size() - 1);
        assertThat(done.type()).isEqualTo(AgentStreamEventType.DONE);
        // DONE 的 answer 必须等于所有 ANSWER_DELTA 拼接，且不含工具轮内容
        assertThat(done.result().getAnswer()).isEqualTo("公司制度");
        assertThat(done.result().isRagUsed()).isTrue();
        assertThat(done.result().getToolContext()).isEqualTo("ctx");
    }

    /**
     * 工具轮只产出 TOOL_END、没有任何 ANSWER_DELTA 时，answer 不能混入工具相关内容；
     * 末轮 FINISHED 帧的整轮全文用于兜底，避免落库空答案。
     */
    @Test
    void executeStream_noAnswerChunks_fallsBackToLastRoundText() {
        givenGraphStream(Flux.just(
                toolFinished(),
                frame(MODEL_NODE, new AssistantMessage("完整答案全文"), OutputType.AGENT_MODEL_FINISHED)));

        List<AgentStreamEvent> events = collect(contextOf("tenant_a"), new AtomicBoolean(false));

        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly(AgentStreamEventType.TOOL_END, AgentStreamEventType.DONE);
        assertThat(events.get(1).result().getAnswer()).isEqualTo("完整答案全文");
    }

    @Test
    void executeStream_sourceError_emitsErrorAndNoDone() {
        givenGraphStream(Flux.<NodeOutput>just(modelChunk("半")).concatWith(Flux.error(new IllegalStateException("boom"))));

        List<AgentStreamEvent> events = collect(contextOf("tenant_a"), new AtomicBoolean(false));

        assertThat(events).extracting(AgentStreamEvent::type)
                .containsExactly(AgentStreamEventType.ANSWER_DELTA, AgentStreamEventType.ERROR);
        // 错误路径不得有 DONE：否则 controller 会把半截答案落库
        assertThat(events).noneMatch(e -> e.type() == AgentStreamEventType.DONE);
        assertThat(events.get(1).text()).contains("boom");
        assertThat(meterRegistry.get("rag.agent.stream.error").counter().count()).isEqualTo(1.0);
    }

    @Test
    void executeStream_idleTimeout_emitsTimeoutHintAndNoDone() {
        givenGraphStream(Flux.<NodeOutput>just(modelChunk("半")).concatWith(Flux.never()));

        List<AgentStreamEvent> events = executor.executeStream(
                        List.of(new UserMessage("hi")), SESSION_ID, new AtomicBoolean(false),
                        contextOf("tenant_a"), directExecutor, 1, 5)
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.get(events.size() - 1).type()).isEqualTo(AgentStreamEventType.ERROR);
        assertThat(events.get(events.size() - 1).text()).contains("超时");
        assertThat(events).noneMatch(e -> e.type() == AgentStreamEventType.DONE);
    }

    @Test
    void executeStream_completion_clearsRecorderAndTenantContext() {
        TenantContext.setSchema("tenant_a");
        TenantContext.setTenantId(7L);
        TenantStreamContext ctx = TenantStreamContext.captureNow();
        TenantContext.clear();

        // 在 graph 流被订阅的那一刻读线程上的租户上下文，验证池任务内已恢复
        @SuppressWarnings("unchecked")
        String[] observedSchema = new String[1];
        givenGraphStream(Flux.defer(() -> {
            observedSchema[0] = TenantContext.getSchema();
            return Flux.just(modelChunk("好"));
        }));

        List<AgentStreamEvent> events = executor.executeStream(
                        List.of(new UserMessage("hi")), SESSION_ID, new AtomicBoolean(false),
                        ctx, directExecutor, 60, 5)
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(observedSchema[0]).isEqualTo("tenant_a");
        verify(recorder).clearRecords();
        // 任务结束后线程上的租户上下文必须清空，防止线程池复用串扰
        assertThat(TenantContext.getSchema()).isNull();
    }

    @Test
    void executeStream_clientCancelled_emitsNoDoneAndStillCleansUp() {
        givenGraphStream(Flux.just(modelChunk("半"), modelChunk("截")));

        // 断开标志在建流前已置位（模拟订阅前客户端已断开）
        List<AgentStreamEvent> events = collect(contextOf("tenant_a"), new AtomicBoolean(true));

        assertThat(events).isNotNull();
        assertThat(events).noneMatch(e -> e.type() == AgentStreamEventType.DONE);
        // 断开不是错误：不发 ERROR，也不计入流内失败计数
        assertThat(events).noneMatch(e -> e.type() == AgentStreamEventType.ERROR);
        assertThat(meterRegistry.find("rag.agent.stream.error").counter()).isNull();
        verify(recorder).clearRecords();
    }

    @Test
    void executeStream_poolSaturated_throwsFromMethodCallNotFromSubscription() {
        givenGraphStream(Flux.just(modelChunk("好")));
        Executor rejectingExecutor = task -> {
            throw new RejectedExecutionException("pool saturated");
        };

        assertThatThrownBy(() -> executor.executeStream(List.of(new UserMessage("hi")), SESSION_ID,
                new AtomicBoolean(false), contextOf("tenant_a"), rejectingExecutor, 60, 5))
                .isInstanceOf(RejectedExecutionException.class);
        // 建流阶段失败不属于流内失败，不应产生 ERROR 计数
        assertThat(meterRegistry.find("rag.agent.stream.error").counter()).isNull();
    }

    @Test
    void executeStream_passesGraphInputAndThreadId() {
        givenGraphStream(Flux.just(modelChunk("好")));
        List<Message> messages = List.of(new UserMessage("公司报销政策"));

        collect(contextOf("tenant_a"), new AtomicBoolean(false), messages);

        ArgumentCaptor<Map<String, Object>> inputs = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<RunnableConfig> config = ArgumentCaptor.forClass(RunnableConfig.class);
        verify(compiledGraph).stream(inputs.capture(), config.capture());
        assertThat(inputs.getValue().get("messages")).isEqualTo(messages);
        assertThat(inputs.getValue().get(OverAllState.DEFAULT_INPUT_KEY)).isEqualTo("公司报销政策");
        assertThat(config.getValue().threadId()).contains(SESSION_ID);
    }

    private List<AgentStreamEvent> collect(TenantStreamContext ctx, AtomicBoolean cancelled,
                                           List<Message> messages) {
        return executor.executeStream(messages, SESSION_ID, cancelled, ctx,
                directExecutor, 60, 5).collectList().block();
    }
}
