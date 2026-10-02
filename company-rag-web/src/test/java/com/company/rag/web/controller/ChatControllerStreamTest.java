package com.company.rag.web.controller;

import com.company.rag.agent.service.AgentResult;
import com.company.rag.agent.service.RagAgentService;
import com.company.rag.agent.stream.AgentStreamEvent;
import com.company.rag.common.model.R;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.rag.eval.answer.AnswerCase;
import com.company.rag.rag.eval.answer.AnswerEvaluationService;
import com.company.rag.rag.memory.RagChatMemory;
import com.company.rag.rag.response.ChatRequest;
import com.company.rag.rag.service.RagSearchService;
import com.company.rag.rag.service.RagSessionService;
import com.company.rag.tenant.context.TenantContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/chat/stream} 流式端点单元测试。
 *
 * <p>用纯 Mockito 直接调用 controller 方法：{@code @WebMvcTest} 需为 controller 全部构造器依赖
 * 提供 mock，而 SSE 端点在 MockMvc 下的断言价值低于对方法体分支的直接覆盖。
 */
class ChatControllerStreamTest {

    private RagAgentService ragAgentService;
    private RagSessionService ragSessionService;
    private RagChatMemory ragChatMemory;
    private AnswerEvaluationService answerEvaluationService;
    private ChatController controller;

    @BeforeEach
    void setUp() {
        ragAgentService = mock(RagAgentService.class);
        ragSessionService = mock(RagSessionService.class);
        ragChatMemory = mock(RagChatMemory.class);
        answerEvaluationService = mock(AnswerEvaluationService.class);

        controller = new ChatController(ragAgentService, mock(RagSearchService.class),
                ragSessionService, ragChatMemory);
        // @Value 与 @Autowired(required=false) 字段在纯单测下不会被注入，需手工赋值
        ReflectionTestUtils.setField(controller, "answerEvaluationService", answerEvaluationService);
        ReflectionTestUtils.setField(controller, "streamEnabled", true);
        ReflectionTestUtils.setField(controller, "evalOnlineEnabled", false);
        ReflectionTestUtils.setField(controller, "asyncEnabled", false);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SecurityUser(7L, 1L, List.of(1L), "tester", "x", "user", true),
                null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private ChatRequest request() {
        return ChatRequest.builder().query("RAG 是什么").sessionId("s-1").build();
    }

    private AgentResult result(boolean ragUsed) {
        return AgentResult.builder().answer("答案").toolContext("轨迹").ragUsed(ragUsed).build();
    }

    @SuppressWarnings("unchecked")
    private Flux<AgentStreamEvent> streamOf(AgentStreamEvent... events) {
        when(ragAgentService.processWithHistoryStream(any(), anyString(), any()))
                .thenReturn(Flux.just(events));
        return (Flux<AgentStreamEvent>) controller.chatStream(request(), 1L);
    }

    @Test
    void streamDisabled_returnsFail503_withoutTouchingService() {
        ReflectionTestUtils.setField(controller, "streamEnabled", false);

        Object outcome = controller.chatStream(request(), 1L);

        assertThat(outcome).isInstanceOf(R.class);
        assertThat(((R<?>) outcome).getCode()).isEqualTo(503);
        verify(ragAgentService, never()).processWithHistoryStream(any(), anyString(), any());
        // 开关关闭时不得读取历史，避免无谓的 Redis/DB 访问
        verify(ragChatMemory, never()).get(anyString());
    }

    @Test
    void missingTenantHeader_throwsIllegalArgument_beforeBuildingStream() {
        assertThatThrownBy(() -> controller.chatStream(request(), null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(ragAgentService, never()).processWithHistoryStream(any(), anyString(), any());
    }

    @Test
    void nonSecurityUserPrincipal_throwsIllegalState() {
        // 有认证信息但 principal 不是 SecurityUser：拿不到可信 userId，必须拒绝服务
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "anonymousUser", null, List.of()));

        assertThatThrownBy(() -> controller.chatStream(request(), 1L))
                .isInstanceOf(IllegalStateException.class);

        verify(ragAgentService, never()).processWithHistoryStream(any(), anyString(), any());
    }

    @Test
    void circuitOpen_returnsFail503() {
        CircuitBreaker cb = mock(CircuitBreaker.class);
        // createCallNotPermittedException 会读 config/name，mock 必须提供否则 NPE；
        // 异常必须在 when(...) 之外构造，否则构成 Mockito 未完成打桩
        when(cb.getCircuitBreakerConfig()).thenReturn(CircuitBreakerConfig.ofDefaults());
        when(cb.getName()).thenReturn("rag-agent");
        CallNotPermittedException open = CallNotPermittedException.createCallNotPermittedException(cb);
        when(ragAgentService.processWithHistoryStream(any(), anyString(), any())).thenThrow(open);

        Object outcome = controller.chatStream(request(), 1L);

        assertThat(((R<?>) outcome).getCode()).isEqualTo(503);
    }

    @Test
    void saturatedExecutor_returnsFail503() {
        when(ragAgentService.processWithHistoryStream(any(), anyString(), any()))
                .thenThrow(new RejectedExecutionException("pool saturated"));

        Object outcome = controller.chatStream(request(), 1L);

        assertThat(((R<?>) outcome).getCode()).isEqualTo(503);
    }

    @Test
    void historyLoaded_whenSessionAndTenantPresent() {
        when(ragAgentService.processWithHistoryStream(any(), anyString(), any()))
                .thenReturn(Flux.empty());

        controller.chatStream(request(), 1L);

        verify(ragChatMemory).get("s-1");
        verify(ragAgentService).processWithHistoryStream(any(), eq("RAG 是什么"), any());
    }

    @Test
    void doneEvent_persistsConversation_withVerifiedIdentity() {
        when(ragSessionService.saveConversation(any(), anyString(), any(), any(), any(), any(),
                isNull(), isNull(), isNull())).thenReturn(99L);

        Flux<AgentStreamEvent> flux = streamOf(AgentStreamEvent.answerDelta("答"),
                AgentStreamEvent.done(result(false)));
        StepVerifier.create(flux).expectNextCount(2).verifyComplete();

        // 落库身份必须是已校验的租户/用户，而不是请求体里客户端可控的值
        verify(ragSessionService).saveConversation(eq(1L), eq("s-1"), eq(7L),
                eq("RAG 是什么"), eq("答案"), eq("轨迹"), isNull(), isNull(), isNull());
    }

    @Test
    void doneEvent_triggersOnlineEvaluation_whenRagUsed() {
        when(ragSessionService.saveConversation(any(), anyString(), any(), any(), any(), any(),
                isNull(), isNull(), isNull())).thenReturn(99L);
        ReflectionTestUtils.setField(controller, "evalOnlineEnabled", true);

        Flux<AgentStreamEvent> flux = streamOf(AgentStreamEvent.done(result(true)));
        StepVerifier.create(flux).expectNextCount(1).verifyComplete();

        ArgumentCaptor<List<AnswerCase>> captor = ArgumentCaptor.forClass(List.class);
        verify(answerEvaluationService, atLeastOnce()).evaluateAllPersisted(captor.capture());
        AnswerCase sent = captor.getValue().get(0);
        assertThat(sent.sessionRowId()).isEqualTo(99L);
        assertThat(sent.tenantId()).isEqualTo(1L);
        assertThat(sent.source()).isEqualTo("online");
        assertThat(sent.context()).isEqualTo("轨迹");
    }

    @Test
    void nullToolContext_normalizedToEmptyForEvaluation() {
        when(ragSessionService.saveConversation(any(), anyString(), any(), any(), any(), any(),
                isNull(), isNull(), isNull())).thenReturn(99L);
        ReflectionTestUtils.setField(controller, "evalOnlineEnabled", true);

        Flux<AgentStreamEvent> flux = streamOf(AgentStreamEvent.done(
                AgentResult.builder().answer("答案").ragUsed(true).build()));
        StepVerifier.create(flux).expectNextCount(1).verifyComplete();

        ArgumentCaptor<List<AnswerCase>> captor = ArgumentCaptor.forClass(List.class);
        verify(answerEvaluationService, atLeastOnce()).evaluateAllPersisted(captor.capture());
        assertThat(captor.getValue().get(0).context()).isEmpty();
    }

    @Test
    void nonRagAnswer_skipsOnlineEvaluation_butStillPersists() {
        when(ragSessionService.saveConversation(any(), anyString(), any(), any(), any(), any(),
                isNull(), isNull(), isNull())).thenReturn(99L);
        ReflectionTestUtils.setField(controller, "evalOnlineEnabled", true);

        Flux<AgentStreamEvent> flux = streamOf(AgentStreamEvent.done(result(false)));
        StepVerifier.create(flux).expectNextCount(1).verifyComplete();

        verify(answerEvaluationService, never()).evaluateAllPersisted(anyList());
        verify(ragSessionService).saveConversation(any(), anyString(), any(), any(), any(), any(),
                isNull(), isNull(), isNull());
    }

    @Test
    void streamEndedWithoutDone_doesNotPersist() {
        Flux<AgentStreamEvent> flux = streamOf(AgentStreamEvent.answerDelta("半"));
        // 未收到 DONE 即结束（模拟断线）：不得落库，避免半截答案污染记忆与评估统计
        StepVerifier.create(flux).expectNextCount(1).verifyComplete();

        verify(ragSessionService, never()).saveConversation(any(), anyString(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void cancel_setsFlagSharedWithService() {
        AtomicReference<AtomicBoolean> shared = new AtomicReference<>();
        when(ragAgentService.processWithHistoryStream(any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    AtomicBoolean flag = invocation.getArgument(2);
                    shared.set(flag);
                    return Flux.<AgentStreamEvent>never().doOnCancel(() -> flag.set(true));
                });

        Flux<AgentStreamEvent> flux =
                (Flux<AgentStreamEvent>) controller.chatStream(request(), 1L);
        StepVerifier.create(flux).thenCancel().verify();

        // 取消必须回传到 service 层收到的同一个标志位对象，池线程据此停止消费 graph 流
        assertThat(shared.get()).isNotNull();
        assertThat(shared.get().get()).isTrue();
    }

    @Test
    void persistFailure_swallowedAndStreamCompletesNormally() {
        doThrow(new RuntimeException("db down")).when(ragSessionService)
                .saveConversation(any(), anyString(), any(), any(), any(), any(), any(), any(), any());

        Flux<AgentStreamEvent> flux = streamOf(AgentStreamEvent.done(result(true)));
        // SSE 已开写，落库异常必须被吞掉并以正常结束收尾（I4）
        StepVerifier.create(flux).expectNextCount(1).verifyComplete();

        // 落库失败时不得触发评估：没有 sessionRowId 的评估记录无法回写
        verify(answerEvaluationService, never()).evaluateAllPersisted(anyList());
    }

    @Test
    void requestThreadContextClearedBeforeReturningFlux() {
        when(ragAgentService.processWithHistoryStream(any(), anyString(), any()))
                .thenReturn(Flux.just(AgentStreamEvent.done(result(false))));

        Object outcome = controller.chatStream(request(), 1L);

        // 返回 Flux 即请求线程结束，上下文必须已清理（防 Tomcat 线程复用串扰）
        assertThat(outcome).isInstanceOf(Flux.class);
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.getSessionId()).isNull();
    }

    /**
     * 流式落库段与阻塞落库段采用「复制不抽取」，实参顺序一旦漂移会<b>静默把数据写进错误租户</b>
     * 且无任何报错。本用例让两条链路在完全相同的身份与内容下各跑一次，逐位比对实参，
     * 把这条隔离红线钉成自动化断言（对应 plan 任务 7 第 6 条）。
     *
     * <p>租户/用户/答案/轨迹四个取值刻意两两不同，任两位互换都会被抓住；
     * 请求体里的 tenantId/userId 填客户端可控的假值，落库必须用请求头与 JWT 的已校验值。
     */
    @Test
    void streamPersistArguments_matchBlockingEndpointArgumentOrder() {
        // 两条链路各用独立请求体：controller 会回写 tenantId/userId，共用对象会让断言失真
        ChatRequest blockingRequest = ChatRequest.builder().query("RAG 是什么").sessionId("s-1")
                .tenantId(999L).userId(888L).build();
        ChatRequest streamingRequest = ChatRequest.builder().query("RAG 是什么").sessionId("s-1")
                .tenantId(999L).userId(888L).build();
        AgentResult same = AgentResult.builder().answer("答案文本").toolContext("轨迹文本")
                .ragUsed(false).build();

        // 按调用顺序抓取九位实参，便于两条链路逐位比对
        List<Object[]> saved = new ArrayList<>();
        doAnswer(invocation -> {
            saved.add(invocation.getArguments());
            return 99L;
        }).when(ragSessionService).saveConversation(any(), any(), any(), any(), any(), any(),
                any(), any(), any());

        // --- 阻塞链路 ---
        when(ragChatMemory.get("s-1")).thenReturn(List.of());
        when(ragAgentService.processWithHistory(List.of(), "RAG 是什么")).thenReturn(same);
        controller.chat(blockingRequest, 3L);

        // --- 流式链路 ---
        when(ragAgentService.processWithHistoryStream(any(), anyString(), any()))
                .thenReturn(Flux.just(AgentStreamEvent.done(same)));
        StepVerifier.create((Flux<AgentStreamEvent>) controller.chatStream(streamingRequest, 3L))
                .expectNextCount(1).verifyComplete();

        assertThat(saved).hasSize(2);
        // 逐位比对：任一位漂移即红（含租户/用户/会话/问/答/轨迹的先后顺序）
        assertThat(saved.get(1)).containsExactlyElementsOf(Arrays.asList(saved.get(0)));
        // 再钉一次隔离语义本身：租户来自请求头、用户来自 JWT，请求体假值一律不落库
        assertThat(saved.get(1)[0]).isEqualTo(3L);
        assertThat(saved.get(1)[2]).isEqualTo(7L);
    }
}
