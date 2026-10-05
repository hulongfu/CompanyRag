package com.company.rag.web.controller;

import com.company.rag.agent.service.AgentResult;
import com.company.rag.agent.service.RagAgentService;
import com.company.rag.agent.stream.AgentStreamEvent;
import com.company.rag.agent.stream.AgentStreamEventType;
import com.company.rag.common.model.R;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.rag.eval.answer.AnswerCase;
import com.company.rag.rag.eval.answer.AnswerEvaluationService;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.memory.RagChatMemory;
import com.company.rag.rag.response.ChatRequest;
import com.company.rag.rag.response.ChatResponse;
import com.company.rag.rag.service.RagSearchService;
import com.company.rag.rag.service.RagSessionService;
import com.company.rag.rag.service.support.RagResultContextBuilder;
import com.company.rag.tenant.context.TenantContextSnapshot;
import com.company.rag.tenant.context.TenantContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

/**
 * 统一对话 Controller
 * 整合原有 AgentController 和 RagController，使用 Agent 模式
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {
    
    private final RagAgentService ragAgentService;
    private final RagSearchService ragSearchService;
    private final RagSessionService ragSessionService;
    // 只读会话历史：Controller 层唯一读取入口，身份取自 TenantContext（可信）
    private final RagChatMemory ragChatMemory;

    // 在线评估服务：可选注入（enabled=false 时为 null），主链路不得因评估 bean 缺失而启动失败
    @Autowired(required = false)
    private AnswerEvaluationService answerEvaluationService;

    @Value("${rag.eval.online-enabled:false}")
    private boolean evalOnlineEnabled;

    @Value("${rag.eval.async-enabled:true}")
    private boolean asyncEnabled;

    // 流式端点灰度开关：默认关闭。未开启时不建流、不读历史、不占线程池，直接返回 503
    @Value("${rag.agent.stream.enabled:false}")
    private boolean streamEnabled;

    // Java 17 兼容：使用普通命名线程工厂（Thread.ofVirtual 为 Java 21 API，本项目 java=17，编译会失败）
    private final ThreadFactory evalThreadFactory = new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger(0);
        @Override public Thread newThread(Runnable r) {
            return new Thread(r, "eval-online-" + n.incrementAndGet());
        }
    };
    private final ExecutorService evalExecutor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(50),
            evalThreadFactory,
            new ThreadPoolExecutor.AbortPolicy());
    
    /**
     * 统一对话入口（Agent 编排，LLM 自动决定调用工具）
     * 
     * @param request 聊天请求
     * @return 聊天响应
     */
    @PostMapping("/chat")
    @PreAuthorize("isAuthenticated()")
    public R<ChatResponse> chat(@RequestBody ChatRequest request,
                                @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        log.info("收到聊天请求：query={}, sessionId={}, headerTenantId={}", 
                request.getQuery(), request.getSessionId(), headerTenantId);
        
        // 1. 设置租户和会话上下文（用于工具调用时获取）
        TenantContext.setSessionId(request.getSessionId());
        
        try {
            // 【安全关键】必须使用请求头中的租户 ID（已经过 JwtAuthenticationFilter 验证）
            // 请求体中的 tenantId 是客户端可控的，完全不可信任，直接忽略
            Long verifiedTenantId = headerTenantId;
            
            // 【关键校验】租户 ID 必须存在，这是多租户隔离的底线
            if (verifiedTenantId == null) {
                log.error("租户 ID 缺失，拒绝服务：query={}", request.getQuery());
                throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
            }
            
            // 将已验证的租户 ID 设置到请求对象中（供后续使用）
            request.setTenantId(verifiedTenantId);
            TenantContext.setTenantId(verifiedTenantId);
            
            // 【安全关键】用户 ID 必须从已认证的安全上下文中获取，不能信任请求体
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            Long verifiedUserId = null;
            if (principal instanceof SecurityUser) {
                verifiedUserId = ((SecurityUser) principal).getUserId();
            }
            
            // 【关键校验】用户 ID 必须存在，这是审计追踪的底线
            if (verifiedUserId == null) {
                log.error("用户 ID 缺失，拒绝服务：principal={}, tenantId={}", 
                        principal != null ? principal.getClass().getSimpleName() : "null", 
                        verifiedTenantId);
                throw new IllegalStateException("用户 ID 不能为空，请确认用户已正确登录");
            }
            
            // 将已验证的用户 ID 设置到请求对象中（供后续使用）
            request.setUserId(verifiedUserId);
            TenantContext.setUserId(verifiedUserId);
            
            // 使用 RagAgentService 处理（Agent 模式，LLM 自动决定调用工具）
            // 如果有 sessionId 和 tenantId，读取历史会话记录并传入
            AgentResult result;
            if (request.getSessionId() != null && request.getTenantId() != null) {
                // 唯一读取入口：经 RagChatMemory 获取有界历史（内部取 TenantContext 身份 + 转换为 Message）
                List<Message> historyMessages = ragChatMemory.get(request.getSessionId());

                log.debug("加载会话历史：sessionId={}, historySize={}",
                        request.getSessionId(), historyMessages.size() / 2);

                // 调用带历史的处理方法
                result = ragAgentService.processWithHistory(historyMessages, request.getQuery());
            } else {
                // 无 sessionId 或 tenantId 缺失，使用无历史模式
                // 注意：tenantId 缺失时不读取历史，避免"读不到旧记忆却存到租户 1"的割裂
                result = ragAgentService.process(request.getQuery());
            }
            
            // 保存会话和聊天记录（包含自动重命名逻辑）
            // 如果有 sessionId，无论 tenantId 是否为空都保存（为空时使用默认租户 1）
            Long savedRowId = null;
            if (request.getSessionId() != null) {
                savedRowId = ragSessionService.saveConversation(
                        request.getTenantId(),
                        request.getSessionId(),
                        request.getUserId(),
                        request.getQuery(),
                        result.getAnswer(),
                        result.getToolContext(),
                        null, null, null
                );
                log.debug("保存会话记录：sessionId={}, tenantId={}, userId={}, savedRowId={}", 
                        request.getSessionId(), request.getTenantId(), request.getUserId(), savedRowId);
            }
            
            ChatResponse response = ChatResponse.builder()
                    .answer(result.getAnswer())
                    .sessionRowId(savedRowId)
                    .build();
            
            log.info("聊天响应完成：answerLength={}, toolContext={}", 
                    response.getAnswer() != null ? response.getAnswer().length() : 0,
                    result.getToolContext());

            // 在线自动评估（默认关闭）：异步触发，失败不影响主回复。
            // answerEvaluationService 为可选注入，enabled=false 时为 null，此处判空跳过（主链路不受影响）
            // 仅对真实执行过 RAG 检索（searchKnowledgeBase）的回答评估：非 RAG 回复无检索上下文，
            // faithfulness 会恒判 0 分（faithfulness 评估依赖 citations= 正文），评估结果无意义且污染统计。
            if (evalOnlineEnabled && savedRowId != null && answerEvaluationService != null && result.isRagUsed()) {
                String queryForEval = request.getQuery();
                String answerForEval = result.getAnswer();
                // 【铁律】context 可能为 null（无工具调用时），空值归一化为 ""，
                // faithfulness 对空上下文按「无法印证」处理而非报错
                String contextForEval = result.getToolContext() == null ? "" : result.getToolContext();
                Long rowIdForEval = savedRowId;
                // 显式快照并恢复租户/日志链路上下文，评估任务内不依赖自身 ThreadLocal
                TenantContextSnapshot ctxSnapshot = TenantContextSnapshot.captureNow();
                if (asyncEnabled) {
                    try {
                        // Java 17 兼容：有界线程池异步，不引入 Java 21 的 Thread.ofVirtual
                        evalExecutor.submit(() -> {
                            try {
                                ctxSnapshot.apply();  // 写回租户/用户/会话/日志链路（值来自主线程显式捕获）
                                // 在线需落库（带 session_row_id 定位语义）：evaluateAllPersisted
                                // 强制校验 tenantId=verifiedTenantId，写 Redis + PG，失败剔除不回抛
                                answerEvaluationService.evaluateAllPersisted(List.of(
                                        new AnswerCase(queryForEval, contextForEval, answerForEval,
                                                verifiedTenantId, rowIdForEval, "online")));
                            } finally {
                                ctxSnapshot.clear();  // 清理，防线程池复用串扰（在池内线程执行，不碰主线程）
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        // 队列满被拒，丢弃本次评估并告警，不阻塞主回复。
                        // 铁律：此处不调用 ctxSnapshot.clear()，它是主线程捕获的快照，
                        // 主线程上下文在 chat() finally 自有清理，这里 clear 会截断主请求日志链路
                        log.warn("[EVAL] 在线评估线程池已满，丢弃一次评估：query={}", queryForEval);
                    }
                } else {
                    // async-enabled=false（同步调试）：
                    try {
                        ctxSnapshot.apply();
                        answerEvaluationService.evaluateAllPersisted(List.of(
                                new AnswerCase(queryForEval, contextForEval, answerForEval,
                                        verifiedTenantId, rowIdForEval, "online")));
                    } finally {
                        ctxSnapshot.clear();
                    }
                }
            }

            return R.ok(response);
            
        } finally {
            // 2. 清理上下文（防止内存泄漏）
            TenantContext.clear();
        }
    }
    
    /**
     * 流式对话入口（SSE）。与阻塞式 {@link #chat} 并存，阻塞端点语义不变。
     *
     * <p><b>返回类型是 {@code Object} 而非 {@code R<T>}，这是本系统唯一偏离统一响应契约的端点</b>：
     * 建流前失败要返回 {@code R<ChatResponse>}，成功要返回 {@code Flux<AgentStreamEvent>}。
     * 与已废弃的 {@code /rag/search} 流式接口同类。
     *
     * <p><b>方法体内的顺序即正确性，不可调整</b>：开关判断必须最先（不建流、不读历史、不占池），
     * 安全校验必须早于建流，建流异常必须在返回 Flux 之前被捕获并转成统一响应体 ——
     * 一旦返回 Flux，Spring 就开始写 SSE 响应头，之后的任何失败都无法再降级为 {@code R<T>}。
     *
     * @param request         聊天请求
     * @param headerTenantId  已由 JwtAuthenticationFilter 校验的租户 ID
     * @return 成功返回 SSE 事件流；建流前失败返回统一失败响应
     */
    // 不声明 produces：SSE 由返回类型 Flux 经 ReactiveTypeHandler 自动识别为 text/event-stream；
    // 建流前失败返回 R<T> 时走 JSON。若在此声明 produces=text/event-stream，Spring 会在 handler
    // 映射阶段把响应类型预设为 SSE，导致 R 找不到 SSE 转换器而抛 HttpMessageNotWritableException → 500。
    @PostMapping("/chat/stream")
    @PreAuthorize("isAuthenticated()")
    public Object chatStream(@RequestBody ChatRequest request,
                             @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        // 1. 灰度开关最先判断：未开启时不读历史、不占线程池
        if (!streamEnabled) {
            log.warn("流式接口未启用，拒绝请求：sessionId={}", request.getSessionId());
            return streamFailBody(503, "流式接口未启用");
        }

        log.info("收到流式聊天请求：query={}, sessionId={}, headerTenantId={}",
                request.getQuery(), request.getSessionId(), headerTenantId);

        TenantContext.setSessionId(request.getSessionId());

        try {
            // 【安全关键】以下校验段与 chat() 逐行一致，改动须同步：
            // 租户 ID 只信请求头（已经过 JwtAuthenticationFilter 验证），请求体里的 tenantId 客户端可控
            Long verifiedTenantId = headerTenantId;
            if (verifiedTenantId == null) {
                log.error("租户 ID 缺失，拒绝流式服务：query={}", request.getQuery());
                throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
            }
            request.setTenantId(verifiedTenantId);
            TenantContext.setTenantId(verifiedTenantId);

            // 【安全关键】用户 ID 只信已认证的安全上下文
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            Long verifiedUserId = null;
            if (principal instanceof SecurityUser) {
                verifiedUserId = ((SecurityUser) principal).getUserId();
            }
            if (verifiedUserId == null) {
                log.error("用户 ID 缺失，拒绝流式服务：principal={}, tenantId={}",
                        principal != null ? principal.getClass().getSimpleName() : "null",
                        verifiedTenantId);
                throw new IllegalStateException("用户 ID 不能为空，请确认用户已正确登录");
            }
            request.setUserId(verifiedUserId);
            TenantContext.setUserId(verifiedUserId);

            // 读历史：与 chat() 同条件，tenantId 缺失时不读历史，避免记忆与落库租户割裂
            List<Message> historyMessages = null;
            if (request.getSessionId() != null && request.getTenantId() != null) {
                historyMessages = ragChatMemory.get(request.getSessionId());
            }

            AtomicBoolean cancelled = new AtomicBoolean(false);
            Flux<AgentStreamEvent> flux;
            try {
                // 建流：此刻尚未返回 Flux，Spring 未开始写 SSE 头，异常可正常转统一响应体
                flux = ragAgentService.processWithHistoryStream(historyMessages, request.getQuery(), cancelled);
            } catch (RejectedExecutionException | CallNotPermittedException e) {
                // 线程池满或熔断打开：降级为 503，不抛到全局异常处理器（I5）
                log.warn("流式建流被拒绝，降级为繁忙响应：sessionId={}, cause={}",
                        request.getSessionId(), e.getMessage());
                return streamFailBody(503, "系统繁忙，请稍后重试");
            }

            // 必须在请求线程捕获租户快照：落库/评估回调运行在流的生产线程上，
            // 而下面的 finally 会清空请求线程上下文，回调阶段只能依赖这份快照
            TenantContextSnapshot snapshot = TenantContextSnapshot.captureNow();
            String query = request.getQuery();
            String sessionId = request.getSessionId();
            // verifiedUserId 经历过条件赋值，不是 effectively final，lambda 需另取副本
            Long streamUserId = verifiedUserId;

            return flux
                    .doOnCancel(() -> cancelled.set(true))
                    .doOnNext(event -> {
                        if (event.type() == AgentStreamEventType.DONE) {
                            persistAndEvaluateStreamResult(event.result(), snapshot,
                                    verifiedTenantId, streamUserId, sessionId, query);
                        }
                    })
                    // 铁律：SSE 已开写后不得让异常穿透到 GlobalExceptionHandler（I4）。
                    // 落库/评估异常已在下方方法内吞掉，此处只兜住不可预期的回调异常。
                    .onErrorResume(e -> {
                        log.error("流式响应异常，结束 SSE 流：sessionId={}", sessionId, e);
                        return Flux.empty();
                    });
        } finally {
            // 与 chat() 一致：在请求线程清理，防线程复用串扰。
            // 不能挂到流的 doFinally 上——终止信号可能落在池线程，会误清池线程自身的上下文。
            TenantContext.clear();
        }
    }

    /**
     * 流式端点「建流前失败」的统一响应体。
     *
     * <p>必须用 {@link ResponseEntity} 显式钉住 {@code Content-Type: application/json}：
     * 流式客户端会带 {@code Accept: text/event-stream}，若直接返回裸 {@code R}，
     * Spring 的内容协商会在「可产出类型」与 Accept 之间求交集失败而返回 406，
     * 破坏「建流前错误一律返回统一响应体」的契约。显式指定 Content-Type 会跳过该协商。
     */
    private ResponseEntity<R<Void>> streamFailBody(int code, String msg) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(R.fail(code, msg));
    }

    /**
     * 流式链路收尾：落库 + 触发在线评估。
     *
     * <p>语义与 {@link #chat} 的落库段与评估段一致（改动须同步），按用户裁决<b>复制不抽取</b>：
     * 抽取需改动阻塞链路方法体，而阻塞链路的评估触发口径是在线评估基线的一部分，不值得为此承担风险。
     *
     * <p><b>只在收到 DONE 时调用</b>：客户端在 DONE 前断开则不落库、不评估，
     * 避免半截答案写进 {@code rag_session} 后被当作历史记忆读回、并污染评估统计。
     *
     * <p>本方法运行在流的生产线程上，<b>禁止读取任何 ThreadLocal</b>：租户/用户/会话三个参数
     * 一律由 controller 方法内已校验的局部变量显式传入；仅在 SQL 路由必需的 schema 上下文上
     * 用请求线程快照做「apply → 执行 → clear」的成对搬运。
     *
     * <p>任何异常都在本方法内吞掉并告警：SSE 已开始写出，抛出无法再转成统一响应体。
     */
    private void persistAndEvaluateStreamResult(AgentResult result,
                                                TenantContextSnapshot snapshot,
                                                Long verifiedTenantId,
                                                Long verifiedUserId,
                                                String sessionId,
                                                String query) {
        if (result == null) {
            return;
        }
        Long savedRowId = null;
        try {
            snapshot.apply();
            if (sessionId != null) {
                savedRowId = ragSessionService.saveConversation(
                        verifiedTenantId,
                        sessionId,
                        verifiedUserId,
                        query,
                        result.getAnswer(),
                        result.getToolContext(),
                        null, null, null
                );
                log.debug("流式保存会话记录：sessionId={}, tenantId={}, userId={}, savedRowId={}",
                        sessionId, verifiedTenantId, verifiedUserId, savedRowId);
            }
        } catch (Exception e) {
            log.error("流式落库失败，跳过在线评估：sessionId={}", sessionId, e);
            return;
        } finally {
            snapshot.clear();
        }

        // 在线自动评估（默认关闭）：触发口径与 chat() 完全一致 —— 仅对真实执行过 RAG 检索的回答评估
        if (!evalOnlineEnabled || savedRowId == null || answerEvaluationService == null || !result.isRagUsed()) {
            return;
        }
        // 【铁律】context 可能为 null（无工具调用时），空值归一化为 ""
        String contextForEval = result.getToolContext() == null ? "" : result.getToolContext();
        Long rowIdForEval = savedRowId;
        if (!asyncEnabled) {
            try {
                snapshot.apply();
                answerEvaluationService.evaluateAllPersisted(List.of(
                        new AnswerCase(query, contextForEval, result.getAnswer(),
                                verifiedTenantId, rowIdForEval, "online")));
            } catch (Exception e) {
                log.error("流式在线评估失败：sessionRowId={}", rowIdForEval, e);
            } finally {
                snapshot.clear();
            }
            return;
        }
        try {
            evalExecutor.submit(() -> {
                try {
                    snapshot.apply();
                    answerEvaluationService.evaluateAllPersisted(List.of(
                            new AnswerCase(query, contextForEval, result.getAnswer(),
                                    verifiedTenantId, rowIdForEval, "online")));
                } catch (Exception e) {
                    log.error("流式在线评估任务失败：sessionRowId={}", rowIdForEval, e);
                } finally {
                    snapshot.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            // 队列满丢弃本次评估。不调用 snapshot.clear()：它是请求线程捕获的快照，
            // 此处 clear 会误清提交线程的上下文
            log.warn("[EVAL] 流式在线评估线程池已满，丢弃一次评估：query={}", query);
        }
    }

    /**
     * 保留独立 RAG 入口（标记为 Deprecated，供现有前端使用）
     * 
     * @param query RAG 查询
     * @param tenantId 租户 ID
     * @return RAG 结果
     */
    @PostMapping("/rag/search")
    @PreAuthorize("isAuthenticated()")
    @Deprecated
    public R<RagResult> ragSearch(@RequestBody RagQuery query,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        log.info("收到 RAG 检索请求：query={}, headerTenantId={}", query.getQuery(), headerTenantId);
        
        // 【安全关键】必须使用请求头中的租户 ID（已经过 JwtAuthenticationFilter 验证）
        // 请求体中的 tenantId 是客户端可控的，完全不可信任，直接忽略
        if (headerTenantId == null) {
            log.error("租户 ID 缺失，拒绝服务：query={}", query.getQuery());
            throw new IllegalArgumentException("租户 ID 不能为空，请确认请求头 X-Tenant-Id 已设置");
        }
        
        // 将已验证的租户 ID 设置到请求对象中（供后续使用）
        query.setTenantId(headerTenantId);
        
        // 【安全关键】用户 ID 必须从已认证的安全上下文中获取，不能信任请求体 query.getUserId()
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long verifiedUserId = null;
        if (principal instanceof SecurityUser) {
            verifiedUserId = ((SecurityUser) principal).getUserId();
        }
        if (verifiedUserId == null) {
            log.error("用户 ID 缺失，拒绝服务：query={}", query.getQuery());
            throw new IllegalStateException("用户 ID 不能为空，请确认用户已正确登录");
        }
        
        RagResult result = ragSearchService.search(query);
        
        // 【落库 Owner = Controller 层】带 sessionId 时用服务端可信身份自行保存（context 从 chunks 重建）
        if (query.getSessionId() != null) {
            // 补齐会话/用户上下文（幂等），若后续功能依赖可在此读取
            TenantContext.setSessionId(query.getSessionId());
            // 用公共方法从 chunks 重建 context，避免 rag_session.context 静默为空破坏 faithfulness 评估
            String context = RagResultContextBuilder.build(result);
            ragSessionService.saveConversation(
                    headerTenantId, query.getSessionId(), verifiedUserId,
                    query.getQuery(), result.getAnswer(), context,
                    null, null, null);
        }
        
        return R.ok(result);
    }

    /**
     * 更新会话反馈（👍/👎）
     * 
     * @param sessionId 会话 ID
     * @param feedback 反馈值：-1=👎, 0=清除，1=👍
     * @return 操作结果
     */
    @PostMapping("/chat/feedback")
    @PreAuthorize("isAuthenticated()")
    public R<Void> updateFeedback(@RequestParam String sessionId,
                                   @RequestParam Long sessionRowId,
                                   @RequestParam Short feedback,
                                   @RequestHeader(value = "X-Tenant-Id", required = false) Long headerTenantId) {
        log.info("收到反馈更新请求：sessionId={}, sessionRowId={}, feedback={}, headerTenantId={}",
                sessionId, sessionRowId, feedback, headerTenantId);
        
        // 【安全关键】租户 ID 必须从请求头获取（已经过 JwtAuthenticationFilter 验证）
        if (headerTenantId == null) {
            log.error("租户 ID 缺失，拒绝服务：sessionId={}", sessionId);
            throw new IllegalArgumentException("租户 ID 不能为空");
        }
        
        // 【安全关键】用户 ID 必须从已认证的安全上下文中获取
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long verifiedUserId = null;
        if (principal instanceof SecurityUser) {
            verifiedUserId = ((SecurityUser) principal).getUserId();
        }
        
        if (verifiedUserId == null) {
            log.error("用户 ID 缺失，拒绝服务：sessionId={}", sessionId);
            throw new IllegalStateException("用户 ID 不能为空");
        }
        
        // 调用 Service 更新反馈（按具体问答行定位）
        ragSessionService.updateFeedback(headerTenantId, verifiedUserId, sessionId, sessionRowId, feedback);
        
        return R.ok();
    }
}
