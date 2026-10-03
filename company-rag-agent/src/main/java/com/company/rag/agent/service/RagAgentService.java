package com.company.rag.agent.service;

import com.company.rag.agent.executor.StreamingAgentExecutor;
import com.company.rag.agent.stream.AgentStreamEvent;
import com.company.rag.agent.stream.TenantStreamContext;
import com.company.rag.common.tool.ToolCallRecord;
import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.tenant.context.TenantContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import io.micrometer.context.ContextSnapshot;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * RAG Agent 服务
 * 基于 Spring AI Alibaba ReactAgent 实现智能工具调用编排
 *
 * Agent 模式工作流程：
 * 1. 用户提问 → ReactAgent 分析意图
 * 2. ReactAgent 自主决定是否需要调用工具或技能（ReAct 模式）
 * 3. 如果需要：自主选择 Tool 或 Skill → 执行 → 将结果反馈给 LLM
 * 4. LLM 基于工具结果生成最终回答
 * 5. 返回给用户
 *
 * 可解释性日志：
 * - traceId 由 Micrometer Tracing 自动写入 MDC，关联所有工具调用
 * - 请求完成后输出 [AGENT] 结构化日志（工具链路、整体耗时）
 */
@Slf4j
@Service
public class RagAgentService {

    private final StreamingAgentExecutor streamingAgentExecutor;
    private final ToolCallRecorder recorder;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * Agent 整体超时时间（分钟），可配置
     * 包括所有工具调用和 LLM 响应时间
     * 配置项：rag.agent.executor.timeout-minutes，默认 5 分钟
     */
    private final int agentTimeoutMinutes;

    /**
     * Agent 核心线程数（可配置）
     */
    private final int corePoolSize;

    /**
     * Agent 最大线程数（可配置）
     */
    private final int maxPoolSize;

    /**
     * Agent 任务队列容量（可配置）
     */
    private final int queueCapacity;

    /**
     * 用于超时控制的线程池
     * 有界线程池：核心/最大/队列均受控，采用 AbortPolicy 拒绝策略降级
     */
    private ExecutorService executorService;

    /**
     * 流式任务专用线程池，与上面的超时池**必须分离**。
     *
     * <p>分离理由：一条流的生命周期可达整体超时上限（默认 5 分钟），且期间线程被
     * {@code blockLast()} 占住。若与阻塞端点共用一个池，几个并发流就能把队列填满，
     * 让原本正常的 {@code /api/chat} 请求被拒；反之阻塞请求堆积也会饿死流式请求。
     *
     * <p>容量刻意小于超时池：流式端点处于灰度开关后面，宁可更早触发拒绝并降级为
     * 统一响应体，也不要让线程数失控。
     */
    private ExecutorService streamExecutor;

    /** 流式线程编号，仅用于线程命名 */
    private static final AtomicInteger STREAM_THREAD_SEQ = new AtomicInteger(0);

    /**
     * 流式相邻事件最大间隔（秒），治「模型 hang 住但 TCP 连接不断」
     */
    private final long streamIdleTimeoutSeconds;

    /**
     * 构造方法，注入 StreamingAgentExecutor 和 ToolCallRecorder
     *
     * <p>流式池参数未显式配置时回退读 {@code rag.agent.executor.*}，
     * 使既有部署不改配置也能直接获得一个与阻塞池同规格的流式池。
     */
    public RagAgentService(StreamingAgentExecutor streamingAgentExecutor,
                           ToolCallRecorder recorder,
                           CircuitBreakerRegistry circuitBreakerRegistry,
                           @org.springframework.beans.factory.annotation.Value("${rag.agent.executor.core-pool-size:4}") int corePoolSize,
                           @org.springframework.beans.factory.annotation.Value("${rag.agent.executor.max-pool-size:8}") int maxPoolSize,
                           @org.springframework.beans.factory.annotation.Value("${rag.agent.executor.queue-capacity:100}") int queueCapacity,
                           @org.springframework.beans.factory.annotation.Value("${rag.agent.executor.timeout-minutes:5}") int agentTimeoutMinutes,
                           @org.springframework.beans.factory.annotation.Value("${rag.agent.stream.core-pool-size:${rag.agent.executor.core-pool-size:4}}") int streamCorePoolSize,
                           @org.springframework.beans.factory.annotation.Value("${rag.agent.stream.max-pool-size:${rag.agent.executor.max-pool-size:8}}") int streamMaxPoolSize,
                           @org.springframework.beans.factory.annotation.Value("${rag.agent.stream.queue-capacity:${rag.agent.executor.queue-capacity:100}}") int streamQueueCapacity,
                           @org.springframework.beans.factory.annotation.Value("${rag.agent.stream.idle-timeout-seconds:60}") long streamIdleTimeoutSeconds) {
        this.streamingAgentExecutor = streamingAgentExecutor;
        this.recorder = recorder;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        // 间隔超时必须为正数，否则 Flux.timeout 会立即判定超时
        this.streamIdleTimeoutSeconds = Math.max(streamIdleTimeoutSeconds, 1L);
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.queueCapacity = queueCapacity;
        // 超时时长必须为正数，启动时校验兜底，避免非法配置导致 logical 错误
        this.agentTimeoutMinutes = Math.max(agentTimeoutMinutes, 1);
        // 核心线程数不能大于最大线程数，启动时校验兜底，避免构造异常
        int effectiveMax = Math.max(maxPoolSize, corePoolSize);
        // 创建有界线程池：队列容量受控，超过 capacity 后由 AbortPolicy 直接拒绝并抛出 RejectedExecutionException，
        // 由上层捕获后按“系统繁忙”降级返回，防止线程无界膨胀拖垮应用
        this.executorService = new ThreadPoolExecutor(
                corePoolSize,
                effectiveMax,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy());

        // 流式池同样有界 + AbortPolicy：池满时在**方法返回前**同步抛出
        // RejectedExecutionException，才能被上层降级为统一响应体（不变式 I5）
        int effectiveStreamMax = Math.max(streamMaxPoolSize, streamCorePoolSize);
        this.streamExecutor = new ThreadPoolExecutor(
                streamCorePoolSize,
                effectiveStreamMax,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(streamQueueCapacity),
                // 线程名单独加前缀：日志排查与「流式任务必须跑在流式池」的回归断言都依赖它
                r -> new Thread(r, "rag-agent-stream-" + STREAM_THREAD_SEQ.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());

        log.info("RagAgentService 初始化：streamingAgentExecutor={}, timeout={} minutes, " +
                        "阻塞池 core={}, max={}, queue={}, 流式池 core={}, max={}, queue={}",
                 streamingAgentExecutor != null ? streamingAgentExecutor.getClass().getSimpleName() : "null",
                 this.agentTimeoutMinutes, corePoolSize, effectiveMax, queueCapacity,
                 streamCorePoolSize, effectiveStreamMax, streamQueueCapacity);
    }

    /**
     * 处理 Agent 请求，自动选择工具或技能（无历史记忆）
     * @param userMessage 用户消息
     * @return Agent 处理结果（包含回答和工具上下文）
     */
    public AgentResult process(String userMessage) {
        return processWithHistory(null, userMessage);
    }

    /**
     * 处理 Agent 请求，带会话历史记忆
     * ReactAgent 会自动管理对话历史和工具调用
     *
     * @param history 历史消息列表（按时间升序）
     * @param userMessage 当前用户消息
     * @return Agent 处理结果（包含回答和工具上下文）
     */
    public AgentResult processWithHistory(List<Message> history, String userMessage) {
        long requestStart = System.currentTimeMillis();

        log.info("[AGENT] userMsg=\"{}\", historySize={}",
                userMessage, history != null ? history.size() : 0);

        try {
            // 构建消息列表
            List<Message> messages = new ArrayList<>();
            if (history != null && !history.isEmpty()) {
                messages.addAll(history);
            }
            messages.add(new UserMessage(userMessage));

            // 使用 StreamingAgentExecutor 处理请求，带超时保护
            // StreamingAgentExecutor 会自动：
            // 1. 分析用户意图
            // 2. 自主决定调用 Tool 或 Skill
            // 3. 执行工具/技能并获取结果
            // 4. 基于结果生成最终回答
            AgentResult agentResult = callAgentWithTimeout(messages);

            String response = agentResult.getAnswer();

            // 聚合工具调用记录，输出结构化日志
            long totalMs = System.currentTimeMillis() - requestStart;
            // 工具明细由 executor 工作线程在清理前带出（跨线程 ThreadLocal 不可见），
            // 不再在 controller 线程调用 getAndClearRecords()（恒取到空列表）
            List<ToolCallRecord> records = agentResult.getToolRecords();
            String toolsSummary = records == null ? "" : records.stream()
                    .map(r -> String.format("%s(%dms,%s)", r.getToolName(), r.getDurationMs(), r.getStatus()))
                    .collect(Collectors.joining(", "));
            log.info("[AGENT] tools=[{}], total={}ms", toolsSummary, totalMs);

            return AgentResult.builder()
                    .answer(response != null ? response : "")
                    .toolContext(agentResult.getToolContext() != null ? agentResult.getToolContext() : MDC.get("traceId"))
                    .ragUsed(agentResult.isRagUsed())
                    .toolRecords(agentResult.getToolRecords())
                    .build();

        } catch (Exception e) {
            long totalMs = System.currentTimeMillis() - requestStart;
            log.error("[AGENT] total={}ms, error={}", totalMs, e.getMessage(), e);
            return AgentResult.builder()
                    .answer("抱歉，系统繁忙，请稍后重试。")
                    .toolContext("error:" + e.getMessage())
                    .ragUsed(false)
                    .build();
        }
    }

    /**
     * 流式处理 Agent 请求，带会话历史记忆，返回 SSE 事件流。
     *
     * <p><b>熔断门控是手动的，不用 {@code @CircuitBreaker} 注解</b>：注解路径的分派取决于
     * classpath 上是否有 {@code resilience4j-reactor}，一旦存在就会把熔断判定推迟到订阅时，
     * 那时 SSE 响应头已写出，无法再降级为统一响应体。手动门控保证熔断打开时异常从
     * <b>方法调用本身</b>同步抛出。
     *
     * <p><b>统计口径 = 建流阶段成败率，不含流内失败</b>：流内失败在 executor 内已被降级为
     * ERROR 事件、流以正常终止结束，任何挂在流终止回调上的记账都会把它记成成功，
     * 静默美化失败率。此处熔断器的职责是保护线程池资源，不是统计答案质量；
     * 流内失败由 {@code rag.agent.stream.error} 计数器独立覆盖。
     *
     * @param history     历史消息列表（按时间升序），可为 null
     * @param userMessage 当前用户消息
     * @param cancelled   客户端断开标志，由 controller 在 SSE 回调里置位
     * @return SSE 事件流
     */
    public Flux<AgentStreamEvent> processWithHistoryStream(List<Message> history, String userMessage,
                                                           AtomicBoolean cancelled) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("rag-agent");
        if (!circuitBreaker.tryAcquirePermission()) {
            // 熔断打开：建流前同步抛出，controller 转统一失败响应
            throw CallNotPermittedException.createCallNotPermittedException(circuitBreaker);
        }

        long start = System.nanoTime();
        try {
            List<Message> messages = new ArrayList<>();
            if (history != null && !history.isEmpty()) {
                messages.addAll(history);
            }
            messages.add(new UserMessage(userMessage));

            // 在请求线程捕获租户与观测上下文，池任务线程内恢复（ThreadLocal 不跨线程可见）
            TenantStreamContext context = TenantStreamContext.captureNow();

            Flux<AgentStreamEvent> flux = streamingAgentExecutor.executeStream(
                    messages, TenantContext.getSessionId(), cancelled, context,
                    streamExecutor, streamIdleTimeoutSeconds, agentTimeoutMinutes);

            // 建流成功即记账并归还许可，不等流结束：若把许可挂到流终止回调，
            // 一条流会占住许可长达 timeout-minutes，HALF_OPEN 的探测名额会被长流占满
            circuitBreaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return flux;
        } catch (RuntimeException e) {
            // 建流阶段失败（含池满 RejectedExecutionException）必须用 onError 而非
            // releasePermission：两者都归还许可，但只有 onError 计入失败率。
            // 只归还许可会让失败率恒为 0、熔断器永不打开（静默故障）。
            circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            throw e;
        }
    }

    /**
     * 带超时保护的 Agent 调用
     * 使用 CompletableFuture 实现超时控制，避免 LLM 挂起或 ReAct 循环拖垮请求
     *
     * @param messages 消息列表
     * @return Agent 响应结果
     * @throws TimeoutException 超时异常
     * @throws GraphRunnerException Agent 执行异常
     * @throws Exception 其他异常
     */
    private AgentResult callAgentWithTimeout(List<Message> messages) throws GraphRunnerException, Exception {
        try {
            // 捕获当前线程全部上下文（含 Observation span 与 MDC，Micrometer 自动注入 traceId/spanId），
            // 用 ContextSnapshot 整体传播可确保父 span 的 ObservationScope 在子线程激活，
            // 否则仅复制 MDC 字符串无父 scope，LLM 子 span 关闭时 MDC 会被清空
            ContextSnapshot snapshot = ContextSnapshot.captureAll();
            // 租户上下文是自定义 ThreadLocal，ContextSnapshot 无法捕获，需手动传递
            String tenantSchema = TenantContext.getSchema();
            Long tenantId = TenantContext.getTenantId();
            Long userId = TenantContext.getUserId();
            String tenantCode = TenantContext.getTenantCode();
            String sessionId = TenantContext.getSessionId();

            // 使用 CompletableFuture 包装异步调用，设置超时时间
            // 在 supplyAsync 内部捕获 GraphRunnerException 并包装为 RuntimeException
            CompletableFuture<AgentResult> future = CompletableFuture
                    .supplyAsync(() -> {
                        // 在子线程中恢复 Observation span + MDC 上下文（返回的 Scope 在 try-with-resources 结束时自动还原/清理）
                        try (ContextSnapshot.Scope ignored = snapshot.setThreadLocals()) {
                            // 恢复租户上下文和会话上下文（自定义 ThreadLocal，需手动传递）
                            if (tenantSchema != null) {
                                TenantContext.setSchema(tenantSchema);
                            }
                            if (tenantId != null) {
                                TenantContext.setTenantId(tenantId);
                            }
                            if (userId != null) {
                                TenantContext.setUserId(userId);
                            }
                            if (tenantCode != null) {
                                TenantContext.setTenantCode(tenantCode);
                            }
                            if (sessionId != null) {
                                TenantContext.setSessionId(sessionId);
                            }

                            // 使用 StreamingAgentExecutor 执行 Agent 调用
                            return streamingAgentExecutor.execute(messages);
                        } catch (GraphRunnerException e) {
                            throw new RuntimeException("Agent 执行失败：" + e.getMessage(), e);
                        } finally {
                            // 组件执行结束后，仅清理自定义租户上下文；MDC/Observation 由上面的 Scope.close() 自动还原，避免线程池复用污染
                            TenantContext.clear();
                        }
                    }, executorService);

            return future.get(agentTimeoutMinutes, TimeUnit.MINUTES);

        } catch (TimeoutException e) {
            log.error("[AGENT] 调用超时：timeout={} minutes，请简化问题或减少工具调用", agentTimeoutMinutes);
            throw new TimeoutException(String.format("Agent 调用超时：%d 分钟，可能原因：1) LLM 响应过慢 2) 工具调用次数过多 3) ReAct 循环",
                    agentTimeoutMinutes));
        } catch (Exception e) {
            // 解包装 RuntimeException 中的 GraphRunnerException
            if (e.getCause() instanceof GraphRunnerException) {
                throw (GraphRunnerException) e.getCause();
            }
            log.error("[AGENT] 调用失败：error={}", e.getMessage(), e);
            throw e;
        }
    }
}
