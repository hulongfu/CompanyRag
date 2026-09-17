package com.company.rag.web.controller;

import com.company.rag.agent.service.AgentResult;
import com.company.rag.agent.service.RagAgentService;
import com.company.rag.common.model.R;
import com.company.rag.common.security.SecurityUser;
import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.eval.answer.AnswerCase;
import com.company.rag.rag.eval.answer.AnswerEvaluationService;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.response.ChatRequest;
import com.company.rag.rag.response.ChatResponse;
import com.company.rag.rag.service.RagSearchService;
import com.company.rag.rag.service.RagSessionService;
import com.company.rag.rag.workflow.TenantContextSnapshot;
import com.company.rag.tenant.context.TenantContext;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;

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

    // 在线评估服务：可选注入（enabled=false 时为 null），主链路不得因评估 bean 缺失而启动失败
    @Autowired(required = false)
    private AnswerEvaluationService answerEvaluationService;

    @Value("${rag.eval.online-enabled:false}")
    private boolean evalOnlineEnabled;

    @Value("${rag.eval.async-enabled:true}")
    private boolean asyncEnabled;

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
                // 读取历史会话（按时间升序）
                List<RagSession> historySessions = ragSessionService.getSessionDetail(
                        request.getTenantId(), verifiedUserId, request.getSessionId());
                
                // 转换为 Message 列表
                List<Message> historyMessages = new ArrayList<>();
                for (RagSession session : historySessions) {
                    historyMessages.add(new UserMessage(session.getQuery()));
                    historyMessages.add(new AssistantMessage(session.getAnswer()));
                }
                
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
            if (evalOnlineEnabled && savedRowId != null && answerEvaluationService != null) {
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
        
        RagResult result = ragSearchService.search(query);
        
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
