package com.company.rag.agent.service;

import com.company.rag.agent.executor.StreamingAgentExecutor;
import com.company.rag.common.tool.ToolCallRecord;
import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * - 每次请求生成 traceId，关联所有工具调用
 * - 请求完成后输出 [AGENT] 结构化日志（traceId、工具链路、整体耗时）
 */
@Slf4j
@Service
public class RagAgentService {

    private final StreamingAgentExecutor streamingAgentExecutor;
    private final ToolCallRecorder recorder;

    /**
     * Agent 整体超时时间（分钟）
     * 包括所有工具调用和 LLM 响应时间
     */
    private static final int AGENT_TIMEOUT_MINUTES = 5;

    /**
     * 用于超时控制的线程池
     */
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    /**
     * 构造方法，注入 StreamingAgentExecutor 和 ToolCallRecorder
     */
    public RagAgentService(StreamingAgentExecutor streamingAgentExecutor,
                           ToolCallRecorder recorder) {
        this.streamingAgentExecutor = streamingAgentExecutor;
        this.recorder = recorder;

        log.info("RagAgentService 初始化：streamingAgentExecutor={}, timeout={} minutes",
                 streamingAgentExecutor != null ? streamingAgentExecutor.getClass().getSimpleName() : "null",
                 AGENT_TIMEOUT_MINUTES);
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
        // 生成 traceId 并设置到当前线程
        String traceId = recorder.generateTraceId();
        recorder.setTraceId(traceId);
        long requestStart = System.currentTimeMillis();

        log.info("[AGENT] traceId={}, userMsg=\"{}\", historySize={}",
                traceId, userMessage, history != null ? history.size() : 0);

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
            AssistantMessage agentResult = callAgentWithTimeout(messages);

            String response = agentResult.getText();

            // 聚合工具调用记录，输出结构化日志
            long totalMs = System.currentTimeMillis() - requestStart;
            List<ToolCallRecord> records = recorder.getAndClearRecords(traceId);
            String toolsSummary = records.stream()
                    .map(r -> String.format("%s(%dms,%s)", r.getToolName(), r.getDurationMs(), r.getStatus()))
                    .collect(Collectors.joining(", "));
            log.info("[AGENT] traceId={}, tools=[{}], total={}ms", traceId, toolsSummary, totalMs);

            return new AgentResult(response != null ? response : "", traceId);

        } catch (Exception e) {
            long totalMs = System.currentTimeMillis() - requestStart;
            log.error("[AGENT] traceId={}, total={}ms, error={}", traceId, totalMs, e.getMessage(), e);
            return new AgentResult("抱歉，系统繁忙，请稍后重试。", "error:" + e.getMessage());
        } finally {
            recorder.clearTraceId();
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
    private AssistantMessage callAgentWithTimeout(List<Message> messages) throws GraphRunnerException, Exception {
        try {
            // 在提交异步任务前，捕获当前线程的租户上下文和会话上下文
            // 因为 ThreadLocal 不会自动传递给子线程，需要手动传递
            String tenantSchema = TenantContext.getSchema();
            Long tenantId = TenantContext.getTenantId();
            Long userId = TenantContext.getUserId();
            String tenantCode = TenantContext.getTenantCode();
            String sessionId = TenantContext.getSessionId();

            // 使用 CompletableFuture 包装异步调用，设置超时时间
            // 在 supplyAsync 内部捕获 GraphRunnerException 并包装为 RuntimeException
            CompletableFuture<AssistantMessage> future = CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            // 在子线程中恢复租户上下文和会话上下文
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
                            AgentResult result = streamingAgentExecutor.execute(messages);
                            return new AssistantMessage(result.getAnswer());
                        } catch (GraphRunnerException e) {
                            throw new RuntimeException("Agent 执行失败：" + e.getMessage(), e);
                        } finally {
                            // 清理线程上下文，避免线程池复用时的数据污染
                            TenantContext.clear();
                        }
                    }, executorService);

            return future.get(AGENT_TIMEOUT_MINUTES, TimeUnit.MINUTES);

        } catch (TimeoutException e) {
            log.error("[AGENT] 调用超时：timeout={} minutes，请简化问题或减少工具调用", AGENT_TIMEOUT_MINUTES);
            throw new TimeoutException(String.format("Agent 调用超时：%d 分钟，可能原因：1) LLM 响应过慢 2) 工具调用次数过多 3) ReAct 循环",
                    AGENT_TIMEOUT_MINUTES));
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
