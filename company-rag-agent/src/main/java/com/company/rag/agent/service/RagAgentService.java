package com.company.rag.agent.service;

import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.agent.tool.ApiDocTool;
import com.company.rag.agent.tool.CodeSearchTool;
import com.company.rag.agent.tool.DatabaseQueryTool;
import com.company.rag.common.tool.ToolCallRecord;
import com.company.rag.common.tool.ToolCallRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG Agent 服务
 * 基于 Spring AI ChatClient 实现智能工具调用编排
 * 
 * Agent 模式工作流程：
 * 1. 用户提问 → ChatClient 分析意图
 * 2. ChatClient 自动决定是否需要调用工具（Function Calling）
 * 3. 如果需要：自动选择工具 → 执行 → 将结果反馈给 LLM
 * 4. LLM 基于工具结果生成最终回答
 * 5. 流式返回给用户
 * 
 * 可解释性日志：
 * - 每次请求生成 traceId，关联所有工具调用
 * - 请求完成后输出 [AGENT] 结构化日志（traceId、工具链路、整体耗时）
 */
@Slf4j
@Service
public class RagAgentService {

    private final ChatModel chatModel;
    private final ToolCallbackProvider toolCallbackProvider;
    private final AgentToolRegistry toolRegistry;
    private final ToolCallRecorder recorder;
    
    // 缓存的 ChatClient 实例
    private volatile ChatClient cachedChatClient;
    // 缓存 ChatClient 构建时的工具列表版本号
    private volatile int cachedToolVersion;

    /**
     * 构造方法，初始化必要组件并构建初始 ChatClient
     */
    public RagAgentService(ChatModel chatModel, 
                           ToolCallbackProvider toolCallbackProvider,
                           AgentToolRegistry toolRegistry,
                           ToolCallRecorder recorder) {
        this.chatModel = chatModel;
        this.toolCallbackProvider = toolCallbackProvider;
        this.toolRegistry = toolRegistry;
        this.recorder = recorder;
        
        // 初始构建 ChatClient(此时 MCP 工具可能还未注册)
        rebuildChatClient();
        
        // 调试日志：输出工具信息
        log.info("RagAgentService 初始化：chatModel={}, toolCallbackProvider={}, initialToolVersion={}", 
                 chatModel.getClass().getSimpleName(), 
                 toolCallbackProvider != null ? toolCallbackProvider.getClass().getSimpleName() : "null",
                 cachedToolVersion);
    }
    
    /**
     * 重建 ChatClient(当工具列表变化时调用)
     */
    private void rebuildChatClient() {
        this.cachedChatClient = ChatClient.builder(chatModel)
                .defaultToolCallbacks(toolCallbackProvider)
                .build();
        this.cachedToolVersion = toolRegistry.getVersion();
        log.debug("重建 ChatClient，当前工具版本号：{}", cachedToolVersion);
    }
    
    /**
     * 获取 ChatClient(带缓存检查)
     * 如果工具列表已更新，则重建 ChatClient
     */
    private ChatClient getChatClient() {
        int currentVersion = toolRegistry.getVersion();
        if (cachedChatClient == null || cachedToolVersion != currentVersion) {
            log.info("检测到工具列表变化 (oldVersion={}, newVersion={})，重建 ChatClient", 
                     cachedToolVersion, currentVersion);
            rebuildChatClient();
        }
        return cachedChatClient;
    }

    /**
     * 处理 Agent 请求，自动选择工具（无历史记忆）
     * @param userMessage 用户消息
     * @return Agent 处理结果（包含回答和工具上下文）
     */
    public AgentResult process(String userMessage) {
        return processWithHistory(null, userMessage);
    }

    /**
     * 处理 Agent 请求，带会话历史记忆（三级窗口控制）
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
            // 获取 ChatClient(带缓存检查，工具列表变化时自动重建)
            ChatClient chatClient = getChatClient();
            
            // 构建 prompt：如果有历史，使用三级窗口控制策略
            var promptSpec = chatClient.prompt();
            
            if (history != null && !history.isEmpty()) {
                List<Message> windowedHistory = applyWindowControl(chatClient, history);
                log.debug("[AGENT] traceId={}, 窗口控制后消息数：{}", traceId, windowedHistory.size());
                promptSpec.messages(windowedHistory);
            }
            
            // ChatClient 自动处理工具调用（Function Calling）
            String response = promptSpec
                    .user(userMessage)
                    .call()
                    .content();
            
            // 聚合工具调用记录，输出结构化日志
            long totalMs = System.currentTimeMillis() - requestStart;
            List<ToolCallRecord> records = recorder.getAndClearRecords(traceId);
            String toolsSummary = records.stream()
                    .map(r -> String.format("%s(%dms,%s)", r.getToolName(), r.getDurationMs(), r.getStatus()))
                    .collect(Collectors.joining(", "));
            log.info("[AGENT] traceId={}, tools=[{}], total={}ms", traceId, toolsSummary, totalMs);
            
            return new AgentResult(response != null ? response : "", null);
            
        } catch (Exception e) {
            long totalMs = System.currentTimeMillis() - requestStart;
            log.error("[AGENT] traceId={}, total={}ms, error={}", traceId, totalMs, e.getMessage(), e);
            return new AgentResult("抱歉，系统繁忙，请稍后重试。", "error:" + e.getMessage());
        } finally {
            recorder.clearTraceId();
        }
    }

    /**
     * 三级窗口控制策略：
     * 1. 第一级：直接注入所有历史（最简单）
     * 2. 第二级：LLM 摘要压缩早期对话
     * 3. 第三级：硬窗口截取最近 N 轮
     */
    private static final int MAX_TOKENS = 4000;           // 最大 token 数
    private static final int COMPRESSION_THRESHOLD = 8000; // 触发压缩的 token 阈值
    private static final int MAX_HISTORY_ROUNDS = 10;     // 硬窗口：最多 10 轮

    private List<Message> applyWindowControl(ChatClient chatClient, List<Message> history) {
        int estimatedTokens = estimateTokens(history);
        
        if (estimatedTokens <= MAX_TOKENS) {
            // 第一级：完整历史在限制内，直接使用
            log.debug("窗口控制：使用完整历史，{} 轮，估计 {} tokens", history.size() / 2, estimatedTokens);
            return history;
            
        } else if (estimatedTokens <= COMPRESSION_THRESHOLD) {
            // 第二级：使用 LLM 摘要压缩
            log.debug("窗口控制：历史过长 ({} tokens)，使用 LLM 压缩", estimatedTokens);
            return compressHistoryWithLLM(chatClient, history);
            
        } else {
            // 第三级：硬窗口截断
            log.info("窗口控制：历史过长 ({} tokens)，截断保留最近 {} 轮", estimatedTokens, MAX_HISTORY_ROUNDS);
            return truncateHistory(history, MAX_HISTORY_ROUNDS);
        }
    }

    /**
     * 使用 LLM 压缩历史对话
     * 策略：保留最近 3 轮完整对话，压缩早期对话为摘要
     */
    private List<Message> compressHistoryWithLLM(ChatClient chatClient, List<Message> history) {
        if (history.size() <= 6) { // 3 轮对话 = 6 条消息
            return history;
        }
        
        // 分离早期对话和最近对话
        int keepFullRounds = 3;
        int keepFullMessages = keepFullRounds * 2; // 每轮 = User + Assistant
        List<Message> earlyHistory = history.subList(0, history.size() - keepFullMessages);
        List<Message> recentHistory = history.subList(history.size() - keepFullMessages, history.size());
        
        // 构建早期对话文本
        StringBuilder earlyContext = new StringBuilder();
        for (int i = 0; i < earlyHistory.size(); i += 2) {
            if (i + 1 < earlyHistory.size()) {
                // 使用 getText() 方法获取纯文本内容（Spring AI 1.0.4）
                String userContent = earlyHistory.get(i).getText();
                String assistantContent = earlyHistory.get(i + 1).getText();
                earlyContext.append("用户：").append(userContent).append("\n");
                earlyContext.append("助手：").append(assistantContent).append("\n");
                earlyContext.append("---\n");
            }
        }
        
        // 调用 LLM 压缩早期对话
        String prompt = String.format(
            "请总结以下对话的关键信息，保留重要事实、结论和上下文，150 字以内：\n\n%s",
            earlyContext.toString()
        );
        
        String summary = chatClient.prompt(prompt).call().content();
        
        // 组装消息：摘要 + 最近完整对话
        List<Message> result = new ArrayList<>();
        result.add(new SystemMessage("对话历史摘要：" + summary));
        result.addAll(recentHistory);
        
        return result;
    }

    /**
     * 硬窗口截断：只保留最近 N 轮对话
     */
    private List<Message> truncateHistory(List<Message> history, int maxRounds) {
        int maxMessages = maxRounds * 2; // 每轮 = User + Assistant
        if (history.size() <= maxMessages) {
            return history;
        }
        return history.subList(history.size() - maxMessages, history.size());
    }

    /**
     * 估算 token 数量（简化版：按字符数/4 估算）
     * Spring AI 1.0.4 中，Message 接口的 getText() 方法返回纯文本内容
     */
    private int estimateTokens(List<Message> messages) {
        int totalChars = messages.stream()
            .mapToInt(m -> {
                if (m == null) return 0;
                // 使用 getText() 获取纯文本内容，避免 toString() 包含类名和元数据
                String content = m.getText();
                return content != null ? content.length() : 0;
            })
            .sum();
        return totalChars / 4;  // 粗略估算：4 个字符 ≈ 1 个 token
    }

    /**
     * 直接调用数据库查询工具（保留原有接口，供 AgentController 使用）
     */
    public String queryDatabase(String sql) {
        return toolRegistry.executeTool("database_query", Map.of("sql", sql));
    }

    /**
     * 直接调用代码搜索工具（保留原有接口，供 AgentController 使用）
     */
    public String searchCode(String keyword, String ext) {
        return toolRegistry.executeTool("code_search", Map.of("keyword", keyword, "ext", ext));
    }

    /**
     * 直接调用 API 文档工具（保留原有接口，供 AgentController 使用）
     */
    public String getApiDoc(String filter) {
        return toolRegistry.executeTool("api_doc", Map.of("filter", filter));
    }
}
