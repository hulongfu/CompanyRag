package com.company.rag.agent.service;

import com.company.rag.common.tool.ToolCallRecord;
import com.company.rag.common.tool.ToolCallRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.agent.ReactAgent;
import org.springframework.ai.agent.ReactAgentResult;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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

    private final ReactAgent reactAgent;
    private final ToolCallRecorder recorder;

    /**
     * 构造方法，注入 ReactAgent 和 ToolCallRecorder
     */
    public RagAgentService(ReactAgent reactAgent,
                           ToolCallRecorder recorder) {
        this.reactAgent = reactAgent;
        this.recorder = recorder;
        
        log.info("RagAgentService 初始化：reactAgent={}", 
                 reactAgent != null ? reactAgent.getClass().getSimpleName() : "null");
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
            
            // 使用 ReactAgent 处理请求（ReAct 模式）
            // ReactAgent 会自动：
            // 1. 分析用户意图
            // 2. 自主决定调用 Tool 或 Skill
            // 3. 执行工具/技能并获取结果
            // 4. 基于结果生成最终回答
            ReactAgentResult agentResult = reactAgent.call(messages);
            
            String response = agentResult.getOutput().getContent();
            
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
}
