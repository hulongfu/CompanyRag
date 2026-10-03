package com.company.rag.agent.executor;

import com.company.rag.agent.service.AgentResult;
import com.company.rag.agent.stream.AgentStreamEvent;
import com.company.rag.agent.stream.AgentStreamEventType;
import com.company.rag.agent.stream.NodeOutputMapper;
import com.company.rag.agent.stream.TenantStreamContext;
import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.common.tool.ToolCallRecord;
import com.company.rag.tenant.context.TenantContext;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 流式 Agent 执行器
 *
 * 封装 ReactAgent 的调用，提供：
 * 1. 统一的调用接口
 * 2. 增强的错误处理和日志记录
 * 3. 流式执行入口（走 graph 层的 CompiledGraph.stream）
 *
 * 注意：ReactAgent 自身不暴露 stream()，流式入口在
 * {@code reactAgent.getCompiledGraph().stream(inputs, config)}。
 *
 * @author AI Assistant
 * @since 2026-08-30
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingAgentExecutor {

    private final ReactAgent reactAgent;

    private final ToolCallRecorder recorder;

    private final NodeOutputMapper nodeOutputMapper;

    private final MeterRegistry meterRegistry;

    /**
     * 执行 Agent 调用（使用 ReactAgent）
     *
     * @param messages 消息列表
     * @return Agent 执行结果
     * @throws GraphRunnerException Agent 执行异常
     */
    public AgentResult execute(List<Message> messages) throws GraphRunnerException {
        log.info("[AGENT-EXEC] 开始执行 Agent 调用");

        try {
            AssistantMessage response = reactAgent.call(messages);
            String content = response != null ? response.getText() : "";

            log.info("[AGENT-EXEC] Agent 调用完成，响应长度={}", content.length());

            String toolContext = recorder.captureToolContext();
            // 判断本次是否执行过 RAG 检索（searchKnowledgeBase），供在线评估据此过滤：
            // 仅对真实检索过的回答评估 faithfulness，避免无上下文的普通回复被误判为 0 分。
            boolean ragUsed = recorder.usedTool("searchKnowledgeBase");
            // 取工具明细供上层输出 tools=[...] 日志；必须在清理前（ThreadLocal 线程内）取值
            List<ToolCallRecord> toolRecords = recorder.getAndClearRecords();
            return AgentResult.builder()
                    .answer(content)
                    .toolContext(toolContext)
                    .ragUsed(ragUsed)
                    .toolRecords(toolRecords)
                    .build();
        } catch (GraphRunnerException e) {
            log.error("[AGENT-EXEC] Agent 执行失败 | error={}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("[AGENT-EXEC] Agent 调用异常 | error={}", e.getMessage(), e);
            throw new GraphRunnerException("Agent 调用失败：" + e.getMessage(), e);
        } finally {
            // 清理当前工作线程的工具调用记录，避免线程池复用时记录跨请求残留（串号/泄漏）
            recorder.clearRecords();
        }
    }

    /**
     * 流式执行 Agent 调用，返回 SSE 事件流。
     *
     * <p><b>池满异常从本方法调用本身同步抛出</b>（{@code RejectedExecutionException}），
     * 不是在订阅时 —— 任务在返回 Flux 之前就提交，调用方才能把它降级成统一响应体。
     * 这也是不使用 {@code subscribeOn} 的原因：一旦延迟到订阅时提交，SSE 响应头已写出，
     * 拒绝异常无法再转成业务错误响应。
     *
     * <p><b>整条流的消费封闭在池任务内部</b>，用顺序代码而非 Reactor 终止回调收尾：
     * {@code ToolCallRecorder} 与 {@code TenantContext} 都是 ThreadLocal，只有在同一个
     * 线程栈内从头跑到尾，才能可靠地取到工具上下文并清理上下文。用 {@code doFinally}
     * 组装 DONE 事件则会被 Reactor 丢弃（上游已发出 onComplete）。
     *
     * @param messages           完整消息列表（历史 + 当前用户消息）
     * @param sessionId          会话 ID，作为 graph 的 threadId
     * @param cancelled          客户端断开标志，由下游取消时置位
     * @param ctx                请求线程捕获的租户 / 观测上下文快照
     * @param streamExecutor     流式专用线程池（与阻塞超时池分离）
     * @param idleTimeoutSeconds 相邻事件最大间隔，治「模型 hang 住但连接不断」
     * @param overallTimeoutMinutes 整条流的时间上限
     * @return SSE 事件流，末事件为 DONE（成功）或 ERROR（失败），二者互斥
     */
    public Flux<AgentStreamEvent> executeStream(List<Message> messages,
                                                String sessionId,
                                                AtomicBoolean cancelled,
                                                TenantStreamContext ctx,
                                                Executor streamExecutor,
                                                long idleTimeoutSeconds,
                                                long overallTimeoutMinutes) {
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        // 先提交再返回：池满时此处当场抛 RejectedExecutionException，调用方仍可返回业务错误
        streamExecutor.execute(() -> runStream(sink, messages, sessionId, cancelled, ctx,
                idleTimeoutSeconds, overallTimeoutMinutes));
        return sink.asFlux();
    }

    /**
     * 池任务主体：在同一条线程内恢复上下文 → 消费 graph 流 → 取值 → 清理。
     *
     * <p>绝不向调用方抛异常（I4）：SSE 响应头已写出，异常穿透到全局异常处理器会往
     * 已提交的响应里写 JSON，产生脏帧。
     */
    private void runStream(Sinks.Many<AgentStreamEvent> sink,
                           List<Message> messages,
                           String sessionId,
                           AtomicBoolean cancelled,
                           TenantStreamContext ctx,
                           long idleTimeoutSeconds,
                           long overallTimeoutMinutes) {
        try (ContextSnapshot.Scope ignored = ctx.snapshot().setThreadLocals()) {
            restoreTenantContext(ctx);

            StringBuilder answer = new StringBuilder();
            // 末轮 AGENT_MODEL_FINISHED 的整轮全文，仅在累加结果为空时兜底
            AtomicReference<String> lastRoundText = new AtomicReference<>();

            Flux<NodeOutput> graphStream = graphStream(messages, sessionId);

            graphStream
                    .doOnNext(output -> {
                        String roundText = nodeOutputMapper.roundFinishedText(output);
                        if (roundText != null && !roundText.isBlank()) {
                            lastRoundText.set(roundText);
                        }
                    })
                    .flatMapIterable(nodeOutputMapper::map)
                    .doOnNext(event -> {
                        if (cancelled.get()) {
                            throw new CancellationException("client disconnected");
                        }
                        if (event.type() == AgentStreamEventType.ANSWER_DELTA && event.text() != null) {
                            answer.append(event.text());
                        }
                        // unicast sink 在客户端断开后返回 FAIL_CANCELLED，据此终止上游消费，
                        // 避免模型继续为已断开的连接产出 token
                        Sinks.EmitResult emitResult = sink.tryEmitNext(event);
                        if (emitResult == Sinks.EmitResult.FAIL_CANCELLED) {
                            cancelled.set(true);
                            throw new CancellationException("client disconnected");
                        }
                    })
                    .timeout(Duration.ofSeconds(idleTimeoutSeconds))
                    // 取消信号继续向外抛，交给下面的 catch 处理（不发 ERROR 事件）
                    .onErrorResume(e -> e instanceof CancellationException
                            ? Flux.error(e)
                            : Flux.error(new StreamFailure(e)))
                    .blockLast(Duration.ofMinutes(overallTimeoutMinutes));

            // 走到这里说明整条流正常终止；任何失败都已被上面的 blockLast 抛出，
            // 由 catch 降级为 ERROR 事件，因此错误路径天然不会发出 DONE（不落库半截答案）
            sink.tryEmitNext(AgentStreamEvent.done(buildResult(answer, lastRoundText.get())));
            sink.tryEmitComplete();
        } catch (CancellationException e) {
            // 客户端断开：不发 ERROR 也不发 DONE，controller 据「无 DONE」不落库
            log.info("[STREAM] 客户端已断开，终止流式输出 | sessionId={}", sessionId);
            sink.tryEmitComplete();
        } catch (Exception e) {
            emitStreamError(sink, unwrap(e));
            sink.tryEmitComplete();
        } finally {
            // 必须在池线程内、清理前取值（ThreadLocal）；清理防止线程池复用串扰
            recorder.clearRecords();
            TenantContext.clear();
        }
    }

    /**
     * 组装 DONE 事件载荷。三个取值都依赖 ThreadLocal，必须在池线程内调用。
     *
     * <p>{@code answer} 以累加的增量帧为主口径；若整条流一帧未发（例如模型只在
     * FINISHED 帧给出全文），用末轮全文兜底，避免落库空答案。
     */
    private AgentResult buildResult(StringBuilder answer, String lastRoundText) {
        String text = answer.length() > 0 ? answer.toString() : (lastRoundText != null ? lastRoundText : "");
        return AgentResult.builder()
                .answer(text)
                .toolContext(recorder.captureToolContext())
                // 工具名与阻塞链路 execute() 完全一致，保证两条链路评估口径相同
                .ragUsed(recorder.usedTool("searchKnowledgeBase"))
                .build();
    }

    /**
     * 构造 graph 输入。{@code Agent.buildMessageInput} 是 protected，流式侧自行构造同结构 Map。
     */
    private Flux<NodeOutput> graphStream(List<Message> messages, String sessionId) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("messages", messages);
        inputs.put(OverAllState.DEFAULT_INPUT_KEY, lastUserText(messages));

        CompiledGraph graph = reactAgent.getCompiledGraph();
        if (graph == null) {
            throw new IllegalStateException("ReactAgent 未初始化 CompiledGraph，无法流式执行");
        }
        return graph.stream(inputs, RunnableConfig.builder().threadId(sessionId).build());
    }

    /**
     * 取末条 UserMessage 的文本作为 graph 的 input；无 UserMessage 时返回空串（不抛）。
     */
    private String lastUserText(List<Message> messages) {
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage userMessage) {
                return userMessage.getText() != null ? userMessage.getText() : "";
            }
        }
        return "";
    }

    private void restoreTenantContext(TenantStreamContext ctx) {
        if (ctx.schema() != null) {
            TenantContext.setSchema(ctx.schema());
        }
        if (ctx.tenantId() != null) {
            TenantContext.setTenantId(ctx.tenantId());
        }
        if (ctx.userId() != null) {
            TenantContext.setUserId(ctx.userId());
        }
        if (ctx.tenantCode() != null) {
            TenantContext.setTenantCode(ctx.tenantCode());
        }
        if (ctx.sessionId() != null) {
            TenantContext.setSessionId(ctx.sessionId());
        }
    }

    /**
     * 流内失败统一降级为 ERROR 事件 + 流正常结束：绝不抛给全局异常处理器。
     */
    private void emitStreamError(Sinks.Many<AgentStreamEvent> sink, Throwable cause) {
        meterRegistry.counter("rag.agent.stream.error").increment();
        log.error("[STREAM] 流式执行失败，降级为 ERROR 事件 | error={}", cause.getMessage(), cause);
        sink.tryEmitNext(AgentStreamEvent.error(describe(cause)));
    }

    /**
     * 超时给用户可执行的提示语，其余错误透出原始信息。
     */
    private String describe(Throwable cause) {
        if (isTimeout(cause)) {
            return "回答生成超时，请简化问题或减少工具调用后重试。";
        }
        return "系统繁忙，请稍后重试：" + cause.getMessage();
    }

    private boolean isTimeout(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException) {
                return true;
            }
            // blockLast(Duration) 超时抛 IllegalStateException("Timeout on blocking read...")
            if (t instanceof IllegalStateException && t.getMessage() != null
                    && t.getMessage().startsWith("Timeout on blocking read")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解开 {@link StreamFailure} 与 Reactor 对受检异常的包装，拿到原始异常用于日志与文案。
     */
    private Throwable unwrap(Exception e) {
        if (e instanceof StreamFailure && e.getCause() != null) {
            return e.getCause();
        }
        return e;
    }

    /**
     * 流内失败的包装标记：Reactor 的 {@code onErrorResume} 之后仍会以信号形式传播受检异常，
     * 用包装类把它变成运行时异常，同时保留原始异常供上层还原文案。
     */
    private static class StreamFailure extends RuntimeException {
        StreamFailure(Throwable cause) {
            super(cause);
        }
    }
}
