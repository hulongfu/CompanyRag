package com.company.rag.rag.router;

import com.company.rag.agent.service.RagAgentService;
import com.company.rag.agent.service.AgentResult;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.response.ChatMetrics;
import com.company.rag.rag.response.ChatRequest;
import com.company.rag.rag.response.DebugInfo;
import com.company.rag.rag.service.RagSearchService;
import com.company.rag.rag.service.RagSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 核心路由层
 * 统一路由入口，根据意图分发到不同处理器
 * 实现三级降级策略：
 * - P0: 核心处理失败 → 兜底回答
 * - P1: 意图识别失败 → 默认 DOCUMENT
 * - P1: RAG 失败 → 纯 LLM
 * - P1: Agent 失败 → RAG → LLM
 * - P2: 指标收集、会话保存、调试信息（失败不影响核心）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatRouter {

    private final IntentRecognizer intentRecognizer;
    private final RagSearchService ragSearchService;
    private final RagAgentService ragAgentService;
    private final ChatModel chatModel;
    private final RagSessionService ragSessionService;

    /**
     * 统一路由入口
     *
     * @param request 聊天请求
     * @return 聊天响应
     */
    public com.company.rag.rag.response.ChatResponse route(ChatRequest request) {
        long startTime = System.currentTimeMillis();
        String routePath = "";

        try {
            // 1. 意图识别（带降级）
            IntentResult intentResult = recognizeIntentSafely(request.getQuery());
            IntentType intent = intentResult.getIntent();
            routePath = "intent:" + intent.name().toLowerCase();

            log.info("路由请求 | query={}, intent={}, source={}, confidence={}",
                    request.getQuery(), intent, intentResult.getSource(), intentResult.getConfidence());

            // 2. 根据意图分发处理
            com.company.rag.rag.response.ChatResponse response = processByIntent(request, intent, routePath);

            // 3. 填充指标
            long totalMs = System.currentTimeMillis() - startTime;
            ChatMetrics metrics = ChatMetrics.builder()
                    .totalMs(totalMs)
                    .intent(intent)
                    .routePath(routePath)
                    .build();
            response.setMetrics(metrics);

            // 4. 填充调试信息（如果请求）
            if (Boolean.TRUE.equals(request.getIncludeDebug())) {
                DebugInfo debugInfo = DebugInfo.builder()
                        .intent(intent)
                        .recognizeSource(intentResult.getSource())
                        .confidence(intentResult.getConfidence())
                        .routePath(routePath)
                        .sources(response.getSources())
                        .build();
                response.setDebug(debugInfo);
            }

            return response;

        } catch (Exception e) {
            log.error("路由处理失败 | query={}", request.getQuery(), e);
            // P0 降级：兜底回答
            long totalMs = System.currentTimeMillis() - startTime;
            return createFallbackResponse(totalMs, routePath);
        }
    }

    /**
     * 安全的意图识别（带降级）
     * P1 降级：意图识别失败 → 默认 DOCUMENT
     *
     * @param query 用户查询
     * @return 意图识别结果
     */
    private IntentResult recognizeIntentSafely(String query) {
        try {
            return intentRecognizer.recognize(query);
        } catch (Exception e) {
            log.warn("意图识别异常，降级到默认 DOCUMENT | query={}", query, e);
            // P1 降级：返回默认 DOCUMENT 意图
            return IntentResult.builder()
                    .intent(IntentType.DOCUMENT)
                    .source("error-fallback")
                    .confidence(0.3)
                    .build();
        }
    }

    /**
     * 根据意图分发到不同处理器
     *
     * @param request   聊天请求
     * @param intent   意图类型
     * @param routePath 路由路径
     * @return 聊天响应
     */
    private com.company.rag.rag.response.ChatResponse processByIntent(ChatRequest request, IntentType intent, String routePath) {
        switch (intent) {
            case DOCUMENT:
                return processDocument(request, routePath);
            case DATABASE:
            case CODE:
                return processAgent(request, intent, routePath);
            case CHAT:
                return processChat(request, routePath);
            default:
                log.warn("未知意图类型：{}, 降级到 DOCUMENT", intent);
                return processDocument(request, routePath);
        }
    }

    /**
     * 处理文档检索意图
     * 降级流程：RagService → 失败 → directLLMAnswer
     *
     * @param request   聊天请求
     * @param routePath 路由路径
     * @return 聊天响应
     */
    private com.company.rag.rag.response.ChatResponse processDocument(ChatRequest request, String routePath) {
        try {
            log.info("处理 DOCUMENT 意图 | query={}", request.getQuery());

            // 构建 RAG 查询
            RagQuery ragQuery = buildRagQuery(request);

            // 调用 RAG 服务
            RagResult ragResult = ragSearchService.search(ragQuery);

            // 转换为 ChatResponse
            return com.company.rag.rag.response.ChatResponse.builder()
                    .answer(ragResult.getAnswer())
                    .sources(ragResult.getSessions())
                    .build();

        } catch (Exception e) {
            log.warn("DOCUMENT 意图处理失败，降级到纯 LLM | query={}", request.getQuery(), e);
            // P1 降级：RAG 失败 → 纯 LLM
            return directLLMAnswer(request.getQuery(), routePath + "->llm-fallback");
        }
    }

    /**
     * 处理数据库/代码意图
     * 降级流程：RagAgentService → 失败 → RagService → 失败 → directLLMAnswer
     *
     * @param request   聊天请求
     * @param intent    意图类型
     * @param routePath 路由路径
     * @return 聊天响应
     */
    private com.company.rag.rag.response.ChatResponse processAgent(ChatRequest request, IntentType intent, String routePath) {
        long startTime = System.currentTimeMillis();
        
        try {
            log.info("处理 {} 意图 | query={}", intent.name(), request.getQuery());

            // 调用 Agent 服务
            com.company.rag.agent.service.AgentResult agentResult = ragAgentService.process(request.getQuery());
            String agentAnswer = agentResult.getAnswer();
            String toolContext = agentResult.getToolContext();

            // 保存对话记录（如果有 sessionId）
            if (request.getSessionId() != null && request.getTenantId() != null) {
                try {
                    Long userId = request.getUserId() != null ? request.getUserId() : 1L;
                    ragSessionService.saveConversation(
                            request.getTenantId(), request.getSessionId(), userId,
                            request.getQuery(), agentAnswer, toolContext,
                            0, 0, (int) (System.currentTimeMillis() - startTime));
                } catch (Exception e) {
                    log.warn("保存对话记录失败", e);
                }
            }

            // 如果 Agent 返回了有效回答
            if (agentAnswer != null && !agentAnswer.trim().isEmpty()) {
                return com.company.rag.rag.response.ChatResponse.builder()
                        .answer(agentAnswer)
                        .sources(Collections.singletonList("agent:" + intent.name().toLowerCase()))
                        .build();
            }

            // Agent 返回空，降级到 RAG
            log.warn("Agent 返回空结果，降级到 RAG | query={}", request.getQuery());
            return processDocument(request, routePath + "->rag-fallback");

        } catch (Exception e) {
            log.warn("{} 意图处理失败，降级到 RAG | query={}", intent.name(), request.getQuery(), e);
            // P1 降级：Agent 失败 → RAG
            try {
                return processDocument(request, routePath + "->rag-fallback");
            } catch (Exception ragEx) {
                log.warn("RAG 降级也失败，降级到纯 LLM | query={}", request.getQuery(), ragEx);
                // P1 降级：RAG 也失败 → 纯 LLM
                return directLLMAnswer(request.getQuery(), routePath + "->rag-fallback->llm-fallback");
            }
        }
    }

    /**
     * 处理聊天意图
     * 直接调用 LLM 回答
     *
     * @param request   聊天请求
     * @param routePath 路由路径
     * @return 聊天响应
     */
    private com.company.rag.rag.response.ChatResponse processChat(ChatRequest request, String routePath) {
        log.info("处理 CHAT 意图 | query={}", request.getQuery());
        return directLLMAnswer(request.getQuery(), routePath);
    }

    /**
     * 直接调用 LLM 回答
     * P1 降级：RAG/Agent 失败后的兜底
     *
     * @param query     用户查询
     * @param routePath 路由路径
     * @return 聊天响应
     */
    private com.company.rag.rag.response.ChatResponse directLLMAnswer(String query, String routePath) {
        try {
            log.info("直接调用 LLM 回答 | query={}", query);

            String promptText = buildLLMPrompt(query);
            Prompt prompt = new Prompt(promptText);
            org.springframework.ai.chat.model.ChatResponse springChatResponse = chatModel.call(prompt);
            String answer = springChatResponse != null && springChatResponse.getResult() != null
                    ? springChatResponse.getResult().getOutput().getText()
                    : "抱歉，我无法回答这个问题。";

            return com.company.rag.rag.response.ChatResponse.builder()
                    .answer(answer)
                    .sources(Collections.singletonList("llm-direct"))
                    .build();

        } catch (Exception e) {
            log.error("LLM 直接回答失败 | query={}", query, e);
            // P0 降级：LLM 也失败，返回兜底回答
            return com.company.rag.rag.response.ChatResponse.builder()
                    .answer("抱歉，服务暂时繁忙，请稍后重试。")
                    .sources(Collections.emptyList())
                    .build();
        }
    }

    /**
     * 创建兜底响应
     *
     * @param totalMs   总耗时
     * @param routePath 路由路径
     * @return 兜底响应
     */
    private com.company.rag.rag.response.ChatResponse createFallbackResponse(long totalMs, String routePath) {
        com.company.rag.rag.response.ChatResponse response = com.company.rag.rag.response.ChatResponse.builder()
                .answer("抱歉，服务暂时繁忙，请稍后重试。")
                .sources(Collections.emptyList())
                .build();

        ChatMetrics metrics = ChatMetrics.builder()
                .totalMs(totalMs)
                .routePath(routePath + "->fallback")
                .build();
        response.setMetrics(metrics);

        return response;
    }

    /**
     * 构建 RAG 查询对象
     *
     * @param request 聊天请求
     * @return RAG 查询
     */
    private RagQuery buildRagQuery(ChatRequest request) {
        RagQuery ragQuery = new RagQuery();
        ragQuery.setTenantId(request.getTenantId());
        ragQuery.setQuery(request.getQuery());
        ragQuery.setTopK(request.getTopK() != null ? request.getTopK() : 10);
        ragQuery.setRerankTopK(request.getTopK() != null ? request.getTopK() / 2 : 5);
        ragQuery.setEnableRerank(request.getEnableRerank() != null ? request.getEnableRerank() : true);
        ragQuery.setSessionId(request.getSessionId());
        return ragQuery;
    }

    /**
     * 构建工具上下文
     *
     * @param request 聊天请求
     * @return 工具上下文
     */
    private String buildToolContext(ChatRequest request) {
        // 可以根据需要构建更丰富的上下文
        return "tenantId=" + request.getTenantId() + ", sessionId=" + request.getSessionId();
    }

    /**
     * 构建 LLM 提示词
     *
     * @param query 用户查询
     * @return 提示词
     */
    private String buildLLMPrompt(String query) {
        return String.format(
                "你是一个专业的企业助手。请简洁、准确地回答用户的问题。\n" +
                "如果不确定答案，请诚实告知。\n\n" +
                "用户问题：%s\n" +
                "请回答：",
                query
        );
    }

    /**
     * 获取处理器名称
     * 用于调试和日志
     *
     * @param intent 意图类型
     * @return 处理器名称
     */
    public String getHandlerName(IntentType intent) {
        switch (intent) {
            case DOCUMENT:
                return "DocumentHandler";
            case DATABASE:
                return "DatabaseAgentHandler";
            case CODE:
                return "CodeAgentHandler";
            case CHAT:
                return "ChatHandler";
            default:
                return "UnknownHandler";
        }
    }
}
