package com.company.rag.rag.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 意图识别器
 * 使用混合策略：规则匹配优先，失败降级到 LLM，再失败使用默认 DOCUMENT
 */
@Slf4j
@Component
public class IntentRecognizer {

    /**
     * LLM 聊天模型
     */
    private final ChatModel chatModel;

    /**
     * 置信度阈值
     */
    private static final double CONFIDENCE_THRESHOLD = 0.8;

    /**
     * 默认意图
     */
    private static final IntentType DEFAULT_INTENT = IntentType.DOCUMENT;

    /**
     * 规则列表
     */
    private final List<PatternRule> rules;

    /**
     * 构造函数
     * 初始化 3 类规则：DATABASE, CODE, CHAT
     *
     * @param chatModel LLM 聊天模型
     */
    public IntentRecognizer(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.rules = initializeRules();
    }

    /**
     * 初始化规则
     * 创建 DATABASE, CODE, CHAT 三类意图的规则
     *
     * @return 规则列表
     */
    private List<PatternRule> initializeRules() {
        // DATABASE 规则
        PatternRule databaseRule = PatternRule.builder()
            .intent(IntentType.DATABASE)
            .patterns(List.of(
                ".*数据库.*",
                ".*查询.*数据.*",
                ".*sql.*",
                ".*select.*from.*",
                ".*表结构.*",
                ".*字段.*"
            ))
            .confidence(0.9)
            .build();
        databaseRule.buildAndCompile();

        // CODE 规则
        PatternRule codeRule = PatternRule.builder()
            .intent(IntentType.CODE)
            .patterns(List.of(
                // 代码搜索场景 → CodeSearchTool
                ".*搜索.*代码.*",
                ".*查找.*代码.*",
                ".*哪里.*调用.*",
                ".*包含.*的代码.*",
                ".*使用.*的地方.*",
                ".*代码示例.*",
                ".*代码片段.*",
                // API 文档生成场景 → ApiDocTool
                ".*生成.*API.*",
                ".*API 文档.*",
                ".*扫描.*端点.*",
                ".*获取.*接口.*",
                // 通用代码查询
                ".*怎么实现.*",
                ".*java.*",
                ".*python.*",
                ".*函数.*怎么写.*"
            ))
            .confidence(0.85)
            .build();
        codeRule.buildAndCompile();

        // CHAT 规则
        PatternRule chatRule = PatternRule.builder()
            .intent(IntentType.CHAT)
            .patterns(List.of(
                ".*你好.*",
                ".*谢谢.*",
                ".*再见.*",
                ".*今天.*",
                ".*天气.*",
                ".*在吗.*"
            ))
            .confidence(0.8)
            .build();
        chatRule.buildAndCompile();

        return List.of(databaseRule, codeRule, chatRule);
    }

    /**
     * 识别用户查询的意图
     * 优先规则匹配，失败降级到 LLM，再失败使用默认 DOCUMENT
     *
     * @param query 用户查询
     * @return 意图识别结果
     */
    public IntentResult recognize(String query) {
        // 1. 优先规则匹配
        for (PatternRule rule : rules) {
            if (rule.matches(query)) {
                log.debug("规则匹配意图：{} for query: {}", rule.getIntent(), query);
                return IntentResult.builder()
                    .intent(rule.getIntent())
                    .source("rule")
                    .confidence(rule.getConfidence())
                    .build();
            }
        }

        // 2. 规则匹配失败，降级到 LLM
        log.debug("规则匹配失败，尝试 LLM 识别：{}", query);
        IntentResult llmResult = recognizeByLLM(query);
        if (llmResult != null && llmResult.getConfidence() >= CONFIDENCE_THRESHOLD) {
            return llmResult;
        }

        // 3. LLM 也失败，使用默认意图
        log.debug("LLM 识别失败，使用默认意图：{} for query: {}", DEFAULT_INTENT, query);
        return IntentResult.builder()
            .intent(DEFAULT_INTENT)
            .source("default")
            .confidence(0.5)
            .build();
    }

    /**
     * 使用 LLM 进行意图分类
     *
     * @param query 用户查询
     * @return 意图识别结果
     */
    public IntentResult recognizeByLLM(String query) {
        try {
            String promptText = buildLLMPrompt(query);
            Prompt prompt = new Prompt(promptText);
            ChatResponse response = chatModel.call(prompt);
            if (response == null) {
                log.warn("LLM 返回 null，使用默认意图：{}", query);
                return null;
            }
            String content = response.getResult().getOutput().getText();
            return parseIntentResponse(content);
        } catch (Exception e) {
            log.error("LLM 意图识别失败：{}", query, e);
            return null;
        }
    }

    /**
     * 构建 LLM 提示词
     * 
     * 意图分类说明：
     * - DOCUMENT: 检索企业文档、需求文档、知识库内容（如："API 文档相关的任务是什么"、"需求文档中关于登录的描述"）
     * - DATABASE: 查询数据库、表结构、SQL 相关（如："查询用户表"、"数据库字段"）
     * - CODE: 代码相关操作，包括：
     *   - 代码搜索：在源码中查找代码片段（如："在哪里调用了 UserService"、"搜索包含@Transactional 的代码"）
     *   - API 文档生成：动态扫描当前系统的 API 接口（如："生成 API 文档"、"扫描所有接口"）
     *   - 代码实现：询问代码怎么写、实现方式（如："这个功能怎么实现"、"java 示例"）
     * - CHAT: 日常聊天、问候（如："你好"、"谢谢"）
     *
     * @param query 用户查询
     * @return 提示词
     */
    private String buildLLMPrompt(String query) {
        return String.format(
            "请分析以下用户查询的意图，并返回 JSON 格式的结果。\\n" +
            "意图类型说明：\\n" +
            "- DOCUMENT: 检索企业文档、需求文档、知识库内容（如：\"API 文档相关的任务是什么\"、\"需求文档中关于登录的描述\"）\\n" +
            "- DATABASE: 查询数据库、表结构、SQL 相关（如：\"查询用户表\"、\"数据库字段\"）\\n" +
            "- CODE: 代码相关操作，包括：\\n" +
            "  1. 代码搜索：在源码中查找代码片段（如：\"在哪里调用了 UserService\"、\"搜索包含@Transactional 的代码\"）\\n" +
            "  2. API 文档生成：动态扫描当前系统的 API 接口（如：\"生成 API 文档\"、\"扫描所有接口\"）\\n" +
            "  3. 代码实现：询问代码怎么写、实现方式（如：\"这个功能怎么实现\"、\"java 示例\"）\\n" +
            "- CHAT: 日常聊天、问候（如：\"你好\"、\"谢谢\"）\\n" +
            "返回格式：INTENT: <意图类型>\\nCONFIDENCE: <置信度 0.0-1.0>\\n" +
            "用户查询：%s\\n" +
            "请分析：",
            query
        );
    }

    /**
     * 解析 LLM 响应
     *
     * @param response LLM 响应文本
     * @return 意图识别结果
     */
    public IntentResult parseIntentResponse(String response) {
        if (response == null || response.isEmpty()) {
            return createDefaultResult();
        }

        try {
            String[] lines = response.split("\n");
            IntentType intent = DEFAULT_INTENT;
            double confidence = 0.5;

            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("INTENT:")) {
                    String intentStr = line.substring(7).trim().toUpperCase();
                    try {
                        intent = IntentType.valueOf(intentStr);
                    } catch (IllegalArgumentException e) {
                        log.warn("无效的意图类型：{}", intentStr);
                        intent = DEFAULT_INTENT;
                    }
                } else if (line.startsWith("CONFIDENCE:")) {
                    String confStr = line.substring(11).trim();
                    try {
                        confidence = Double.parseDouble(confStr);
                    } catch (NumberFormatException e) {
                        log.warn("无效的置信度：{}", confStr);
                        confidence = 0.5;
                    }
                }
            }

            return IntentResult.builder()
                .intent(intent)
                .source("llm")
                .confidence(confidence)
                .build();
        } catch (Exception e) {
            log.error("解析 LLM 响应失败：{}", response, e);
            return createDefaultResult();
        }
    }

    /**
     * 创建默认结果
     *
     * @return 默认意图结果
     */
    private IntentResult createDefaultResult() {
        return IntentResult.builder()
            .intent(DEFAULT_INTENT)
            .source("llm")
            .confidence(0.5)
            .build();
    }
}