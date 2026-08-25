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
 * 修复 Skill 调用问题：使用 SkillsAgentHook 加载技能，使 Agent 能自主调用技能
 */
@Slf4j
@Configuration
public class AgentConfig {

    /**
     * 配置 ReactAgent Bean，集成 Skills 技能调用
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
        
        return ReactAgent.builder()
                .name("rag-agent")
                .model(chatModel)
                .toolCallbackProviders(toolCallbackProvider)
                .hooks(List.of(skillsHook))  // 添加 SkillsAgentHook，使 Agent 能调用技能
                .enableLogging(true)  // 启用内置日志输出，将 Agent 的思考过程、工具调用结果打印到控制台
                .build();
    }
}
