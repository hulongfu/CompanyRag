package com.company.rag.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Hermes Agent POC 配置 - 已废弃
 * 
 * 验证结果：
 * ❌ Spring AI 1.0.4 中不存在以下类：
 *   - org.springframework.ai.chat.memory.InMemoryChatMemory
 *   - org.springframework.ai.chat.memory.MessageWindowChatMemory
 *   - org.springframework.ai.chat.client.advisor.ChatMemoryAdvisor
 * 
 * 结论：
 * Spring AI 1.0.4 的会话记忆 API 还不成熟，需要自行实现类似功能。
 * 当前 RagAgentService 的手动记忆管理方式已经足够使用。
 * 
 * 建议：
 * 1. 保持当前 RagAgentService 的实现方式
 * 2. 如需 Redis 持久化，可以自行实现 ChatMemory 接口
 * 3. 关注 Spring AI 后续版本的 ChatMemory API 发展
 */
@Configuration
@Profile("poc")
public class HermesAgentPocConfig {
    // 空配置类，仅用于记录验证失败
}
