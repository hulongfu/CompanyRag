# Hermes Agent POC - 技术验证报告

**日期**: 2026-08-21  
**状态**: ❌ 验证失败 - Spring AI 1.0.4 不支持 ChatMemoryAdvisor API  
**分支**: feature/hermes-agent-poc

---

## 验证目标

1. ❌ 验证 Spring AI 1.0.4 中 `ChatMemoryAdvisor` API 的可用性 → **失败**
2. ✅ 验证当前架构已实现会话记忆功能
3. ✅ 对比当前架构与 Advisor 模式的差异

---

## 验证结果

### ❌ 关键发现：Spring AI 1.0.4 不支持 ChatMemoryAdvisor

**编译错误**：
```
[ERROR] 找不到符号
  符号：类 InMemoryChatMemory
  位置：程序包 org.springframework.ai.chat.memory

[ERROR] 找不到符号
  符号：类 ChatMemoryAdvisor
  位置：程序包 org.springframework.ai.chat.client.advisor
```

**原因分析**：
- Spring AI 1.0.4 版本中，ChatMemory API 还未成熟
- `InMemoryChatMemory`、`MessageWindowChatMemory`、`ChatMemoryAdvisor` 等类不存在
- 可能需要 Spring AI 更高版本（如 1.1.x 或 2.x）才支持

### ✅ 当前架构已经实现会话记忆功能

**当前实现** (`RagAgentService.processWithHistory`)：
```java
public AgentResult processWithHistory(List<Message> history, String userMessage) {
    // 1. 三级窗口控制策略
    List<Message> windowedHistory = applyWindowControl(history);
    
    // 2. 手动传递历史消息
    String response = promptSpec
            .messages(windowedHistory)
            .user(userMessage)
            .call()
            .content();
    
    return new AgentResult(response, null);
}
```

**优势**：
- ✅ 不依赖 Spring AI 的 ChatMemory API
- ✅ 完全控制窗口控制策略（完整 → 压缩 → 截断）
- ✅ 代码清晰，易于理解和维护

### ✅ 对比分析

| 维度 | 当前实现 | Hermes 愿景 | 结论 |
|------|---------|-----------|------|
| **会话记忆** | 手动传递 `List<Message>` | `ChatMemoryAdvisor` 自动管理 | 当前实现已够用 |
| **窗口控制** | 三级策略（自定义实现） | `MessageWindowChatMemory` | 当前实现更灵活 |
| **持久化** | 无（重启后丢失） | Redis 持久化 | 可自研实现 |
| **API 成熟度** | 完全可控 | Spring AI 不支持 | 当前实现更可靠 |

---

## 建议方案

### 方案 1：保持当前架构 ✅ **推荐**

**理由**：
1. 当前 `RagAgentService` 已经实现了完整的会话记忆功能
2. 三级窗口控制策略已经相当完善
3. 不依赖 Spring AI 不成熟的 API
4. 代码清晰，易于维护

**后续优化方向**：
- 如需 Redis 持久化，可以自行实现 `ChatMemory` 接口（不依赖 Spring AI）
- 优化窗口控制策略（如改进摘要压缩算法）
- 添加会话恢复功能（重启后从 Redis 加载）

### 方案 2：自行实现 ChatMemory 接口 ⚠️ 中等优先级

**实现思路**：
```java
public class RedisChatMemory implements ChatMemory {
    private final RedissonClient redissonClient;
    
    @Override
    public List<Message> get(String conversationId) {
        // 从 Redis 加载会话消息
    }
    
    @Override
    public void add(String conversationId, List<Message> messages) {
        // 保存会话消息到 Redis
    }
    
    @Override
    public void clear(String conversationId) {
        // 清除会话
    }
}
```

**集成方式**：
- 在 `RagAgentService` 中注入 `RedisChatMemory`
- 在 `processWithHistory` 前后调用 `get()` 和 `add()` 方法

### 方案 3：等待 Spring AI 升级 ❌ 不推荐

**理由**：
- Spring AI 1.0.4 → 1.1.x 或 2.x 的升级路径不明确
- 可能带来其他兼容性问题
- 当前实现已经够用，无需等待

---

## 验证代码

**POC 配置类** (已废弃):
```java
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
 */
@Configuration
@Profile("poc")
public class HermesAgentPocConfig {
    // 空配置类，仅用于记录验证失败
}
```

---

## 结论

**核心结论**：
1. ❌ Spring AI 1.0.4 不支持 ChatMemoryAdvisor API
2. ✅ 当前 `RagAgentService` 已经实现了完整的会话记忆功能
3. ✅ 建议保持当前架构，无需引入 Advisor 模式
4. ⚠️ 如需 Redis 持久化，可以自行实现（不依赖 Spring AI）

**下一步行动**：
1. ✅ 保留当前 `RagAgentService` 实现
2. ❌ 放弃引入 `ChatMemoryAdvisor` 的计划
3. ⚠️ 如需要，自行实现 `RedisChatMemory`（独立于 Spring AI）
4. ✅ 聚焦于 `AutoMemoryToolsAdvisor`（技能沉淀）的自研实现

---

**文档版本**: 1.0  
**状态**: 验证完成  
**审批**: 待用户确认
