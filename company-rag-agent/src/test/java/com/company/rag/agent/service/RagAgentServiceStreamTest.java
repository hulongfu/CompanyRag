package com.company.rag.agent.service;

import com.company.rag.agent.executor.StreamingAgentExecutor;
import com.company.rag.agent.stream.AgentStreamEvent;
import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.tenant.context.TenantContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RagAgentService#processWithHistoryStream} 的熔断门控与建流语义单测。
 */
class RagAgentServiceStreamTest {

    private StreamingAgentExecutor streamingAgentExecutor;
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private CircuitBreaker circuitBreaker;
    private RagAgentService service;

    @BeforeEach
    void setUp() throws Exception {
        streamingAgentExecutor = mock(StreamingAgentExecutor.class);
        ToolCallRecorder recorder = mock(ToolCallRecorder.class);
        when(recorder.getAndClearRecords()).thenReturn(List.of());
        circuitBreakerRegistry = mock(CircuitBreakerRegistry.class);
        circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreakerRegistry.circuitBreaker("rag-agent")).thenReturn(circuitBreaker);
        when(circuitBreaker.tryAcquirePermission()).thenReturn(true);
        // createCallNotPermittedException 会读 config 决定是否写栈，mock 必须提供否则 NPE
        when(circuitBreaker.getCircuitBreakerConfig()).thenReturn(CircuitBreakerConfig.ofDefaults());
        when(circuitBreaker.getName()).thenReturn("rag-agent");
        service = new RagAgentService(streamingAgentExecutor, recorder, circuitBreakerRegistry,
                1, 2, 10, 5, 1, 2, 10, 60);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 替换内部流式线程池：池满只能靠真实的拒绝策略构造，反射替换比压小容量后并发打更可控。
     */
    private void givenStreamExecutor(ExecutorService executor) throws Exception {
        Field field = RagAgentService.class.getDeclaredField("streamExecutor");
        field.setAccessible(true);
        field.set(service, executor);
    }

    @Test
    void processWithHistoryStream_poolSaturated_throwsFromMethodCallAndRecordsFailure() throws Exception {
        ExecutorService rejecting = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException("pool saturated")).when(rejecting).execute(any(Runnable.class));
        givenStreamExecutor(rejecting);
        // 模拟真实 executeStream 的行为：在方法体内向池提交任务，池满即当场抛出
        when(streamingAgentExecutor.executeStream(anyList(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    java.util.concurrent.Executor executor = invocation.getArgument(4);
                    executor.execute(() -> {
                    });
                    return Flux.empty();
                });

        assertThatThrownBy(() -> service.processWithHistoryStream(List.of(), "hi", new AtomicBoolean(false)))
                .isInstanceOf(RejectedExecutionException.class);

        // 必须记失败而非只归还许可：只 release 会让失败率恒 0、熔断器永不打开
        ArgumentCaptor<RejectedExecutionException> failure =
                ArgumentCaptor.forClass(RejectedExecutionException.class);
        verify(circuitBreaker).onError(anyLong(), eq(TimeUnit.NANOSECONDS), failure.capture());
        assertThat(failure.getValue()).hasMessage("pool saturated");
        // onError 内部已释放许可，再 release 会造成许可虚增
        verify(circuitBreaker, never()).releasePermission();
        verify(circuitBreaker, never()).onSuccess(anyLong(), any());
    }

    @Test
    void processWithHistoryStream_circuitOpen_throwsCallNotPermittedFromMethodCall() {
        when(circuitBreaker.tryAcquirePermission()).thenReturn(false);

        assertThatThrownBy(() -> service.processWithHistoryStream(List.of(), "hi", new AtomicBoolean(false)))
                .isInstanceOf(CallNotPermittedException.class);

        verifyNoInteractions(streamingAgentExecutor);
        verify(circuitBreaker, never()).onSuccess(anyLong(), any());
        verify(circuitBreaker, never()).onError(anyLong(), any(), any());
    }

    @Test
    void processWithHistoryStream_streamCreated_recordsSuccessBeforeMethodReturns() {
        when(streamingAgentExecutor.executeStream(anyList(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(Flux.just(AgentStreamEvent.answerDelta("好")));

        // 未订阅即已完成记账：许可不能挂占整条流时长，否则 HALF_OPEN 探测名额会被长流占满
        Flux<AgentStreamEvent> flux = service.processWithHistoryStream(List.of(), "hi", new AtomicBoolean(false));

        verify(circuitBreaker).onSuccess(anyLong(), eq(TimeUnit.NANOSECONDS));
        assertThat(flux).isNotNull();
    }

    @Test
    void processWithHistoryStream_inStreamFailure_neverRecordedAsCircuitFailure() {
        when(streamingAgentExecutor.executeStream(anyList(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(Flux.just(AgentStreamEvent.answerDelta("半"))
                        .concatWith(Flux.error(new IllegalStateException("boom"))));

        Flux<AgentStreamEvent> flux = service.processWithHistoryStream(List.of(), "hi", new AtomicBoolean(false));
        flux.onErrorResume(e -> Flux.empty()).collectList().block();

        // 流内失败不进熔断统计：挂回调记账会把失败静默记成成功
        verify(circuitBreaker, never()).onError(anyLong(), any(), any());
        verify(circuitBreaker).onSuccess(anyLong(), eq(TimeUnit.NANOSECONDS));
    }

    @Test
    void processWithHistoryStream_buildsMessagesWithHistoryAndCurrentUserMessage() {
        when(streamingAgentExecutor.executeStream(anyList(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(Flux.empty());
        List<Message> history = List.of(new UserMessage("上一轮提问"));
        TenantContext.setSessionId("session-9");

        service.processWithHistoryStream(history, "当前提问", new AtomicBoolean(false));

        ArgumentCaptor<List<Message>> messages = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AtomicBoolean> cancelled = ArgumentCaptor.forClass(AtomicBoolean.class);
        verify(streamingAgentExecutor).executeStream(messages.capture(), sessionId.capture(),
                cancelled.capture(), any(), any(), anyLong(), anyLong());
        assertThat(messages.getValue()).hasSize(2);
        assertThat(messages.getValue().get(1).getText()).isEqualTo("当前提问");
        assertThat(sessionId.getValue()).isEqualTo("session-9");
        assertThat(cancelled.getValue().get()).isFalse();
    }

    @Test
    void processWithHistoryStream_usesConfiguredTimeouts() throws Exception {
        when(streamingAgentExecutor.executeStream(anyList(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(Flux.empty());

        service.processWithHistoryStream(null, "hi", new AtomicBoolean(false));

        verify(streamingAgentExecutor).executeStream(anyList(), any(), any(), any(), any(),
                eq(60L), eq(5L));
        verify(streamingAgentExecutor, never()).execute(anyList());
    }

    @Test
    void processWithHistory_blocksAsBefore_andDoesNotTouchCircuitBreaker() throws Exception {
        // 阻塞链路保持原语义：不接熔断器，异常在内部降级为兜底答案
        when(streamingAgentExecutor.execute(anyList())).thenThrow(new RuntimeException("down"));

        AgentResult result = service.processWithHistory(null, "hi");

        assertThat(result.getAnswer()).isEqualTo("抱歉，系统繁忙，请稍后重试。");
        verifyNoInteractions(circuitBreakerRegistry);
    }
}
