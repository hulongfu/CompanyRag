package com.company.rag.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry;

import java.nio.file.Files;
import java.io.IOException;
import java.util.List;

/**
 * Agent 配置类
 * 配置 Spring AI Alibaba ReactAgent，集成 Skills 技能调用
 * 
 * 注意：ToolCallbackProvider 由 company-rag-rag 模块的 AgentToolConfig 统一提供，
 * 使用 AggregatedToolCallbackProvider 聚合所有工具（包括 MCP 工具）
 * 
 * 设计目标（2026-08-29）：
 * 1. 同时支持 Skills 和 Tools
 * 2. 优先级：用户问题 → 先匹配 Skills → 执行 Skill（Skill 内部可调用 Tools）
 * 3. 无匹配 Skill → 直接调用 Tools（如 searchKnowledgeBase）
 * 
 * 待调查问题：
 * - SkillsAgentHook 是否会覆盖 ToolCallbackProvider 提供的工具定义？
 * - 如何确保 LLM 同时看到 Skills 和 Tools？
 */
@Slf4j
@Configuration
public class AgentConfig {

    /**
     * 配置 ReactAgent Bean，同时支持 Skills 和 Tools
     * ReactAgent 提供 ReAct（Reasoning + Acting）模式的 Agent 实现
     * 可自主分析用户问题，决定调用 Skill 或 Tool
     * 
     * @param chatModel ChatModel 用于 LLM 对话
     * @param toolCallbackProvider Tool 回调提供者（由 AgentToolConfig 提供）
     * @return ReactAgent 实例
     */
    @Bean
    @Description("ReactAgent for autonomous tool and skill invocation")
    public ReactAgent reactAgent(
            ChatModel chatModel,
            ToolCallbackProvider toolCallbackProvider) {
        
        // 配置 Skills 注册中心，扫描 ./agent_skills 目录
        String skillsPath = "./agent_skills";
        log.info("扫描 Skills 目录内容：{}", skillsPath);
        try {
            java.nio.file.Path path = java.nio.file.Paths.get(skillsPath);
            if (Files.exists(path)) {
                Files.list(path).forEach(p ->
                    log.info("  - {}", p.getFileName())
                );
            } else {
                log.warn("Skills 目录不存在：{}", path.toAbsolutePath());
            }
        } catch (IOException e) {
            log.warn("无法列出 Skills 目录内容", e);
        }
        
        // 创建 FileSystemSkillRegistry，扫描外部文件系统中的技能
        FileSystemSkillRegistry skillRegistry = FileSystemSkillRegistry.builder()
                .userSkillsDirectory(skillsPath)  // 使用 String 参数
                .build();
        
        /**
         * SkillsAgentHook：在 Agent 启动时，自动从指定的技能注册表（如文件系统或 classpath）加载所有技能描述。
         * 当用户请求匹配某个技能，钩子会：
         * 1. 拦截请求，优先使用技能的指令模板来引导 LLM。
         * 2. 必要时调用 read_skill 工具读取技能的具体内容（SKILL.md）。
         * 3. 执行技能中定义的步骤（可能包含多次工具调用）
         */
        SkillsAgentHook skillsHook = SkillsAgentHook.builder()
                .skillRegistry(skillRegistry)  // 注入 SkillRegistry
                .build();
        
        log.info("SkillsAgentHook 已创建，包含技能注册表：{}", skillRegistry.getClass().getSimpleName());
        log.info("ToolCallbackProvider 已注入：{}", toolCallbackProvider.getClass().getSimpleName());
        
        ReactAgent agent = ReactAgent.builder()
                .name("rag-agent")
                .systemPrompt("""
                    你是一个智能助手，通过工具 (Tool) 和技能 (Skill) 为用户完成任务。
            
                    ## 决策优先级（从高到低）
                    1. 如果用户请求匹配某个 Skill 的描述，优先使用 Skill（因为 Skill 封装了完整的业务流程）
                    2. 如果无匹配 Skill，则使用 Tool 直接执行
                    3. 复杂任务可组合多个 Tool 完成，Skill 本身已包含组合逻辑时无需额外拆解
            
                    ## 输出格式要求
                    - 在调用技能/工具前，必须先输出你的思考过程（分析用户意图、匹配的技能/工具、选择理由）
                    - 思考过程格式：
                      ```
                      思考：
                      1. 用户意图：...
                      2. 列出匹配的技能/工具列表：...
                      3. 选择理由：...
                      ```
                    - 然后调用技能/工具执行
            
                    ## 约束
                    - 不要主动列出所有 Skill/Tool 清单，直接根据用户请求选择最合适的执行
                    - 涉及知识查询时，优先使用知识库检索工具
                    - 调用 Skill 时，无需重复说明 Skill 内部已包含的步骤，直接执行即可
                    - 如果无法确定用什么，向用户提问澄清，不要猜测
                    """)
                .model(chatModel)
                .toolCallbackProviders(toolCallbackProvider)
                .hooks(List.of(skillsHook))  // 添加 SkillsAgentHook，使 Agent 能调用技能
                .enableLogging(true)  // 启用内置日志输出，将 Agent 的思考过程、工具调用结果打印到控制台
                .build();
        
        // 技能加载成功告警
        log.info("ReactAgent 初始化完成，Skills 和 Tools 已同时启用");
        
        return agent;
    }
}
