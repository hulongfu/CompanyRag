# Hermes Agent 技术验证报告

**日期**: 2026-08-21  
**验证目标**: 评估从当前 Agent 架构演进到 Hermes Agent 的可行性和必要性  
**Spring AI 版本**: 1.0.4  
**Spring Boot 版本**: 3.4.4

---

## 1. 当前架构状态

### 1.1 已具备的 Agent 能力

| 能力 | 实现位置 | 实现方式 |
|------|---------|---------|
| **核心 Agent Loop** | `RagAgentService` | `ChatClient` + Function Calling |
| **工具注册中心** | `AgentToolRegistry` | 统一注册 + MCP 协议支持 |
| **工具编排** | `RagAgentService.processWithHistory` | LLM 自主决策工具调用 |
| **会话记忆（基础）** | `RagAgentService` | 手动传递历史消息列表 |
| **窗口控制** | `RagAgentService.applyWindowControl` | 三级策略（完整/压缩/截断） |

### 1.2 当前架构优势

✅ **已经实现了核心 Agent 能力**：
- `ChatClient` 基于 Spring AI Function Calling 实现工具自主调用
- `AgentToolRegistry` 统一管理所有工具（包括 MCP 外部工具）
- `RagAgentService` 支持会话历史（虽然手动管理）
- 已有三级窗口控制策略（完整 → 压缩 → 截断）

✅ **架构简洁**：
- 没有过度抽象，代码易于理解和维护
- 工具调用流程清晰：`ChatClient → ToolCallbackProvider → AgentToolRegistry`

---

## 2. Hermes Agent 愿景

### 2.1 你设想的架构

```
ChatClient
├── ToolCallingAdvisor        (核心工具调用)
├── SessionMemoryAdvisor      (会话记忆管理 + Redis 持久化)
└── AutoMemoryToolsAdvisor    (技能沉淀 + 自动推荐)
```

### 2.2 预期收益

| Advisor | 职责 | 预期价值 |
|---------|------|---------|
| **ToolCallingAdvisor** | 工具调用编排 | 与当前 `ChatClient.defaultToolCallbacks` 功能重叠 |
| **SessionMemoryAdvisor** | 记忆管理 + 持久化 | ✅ 真正价值点：Redis 存储、会话恢复、智能压缩 |
| **AutoMemoryToolsAdvisor** | 技能沉淀 | ✅ 差异化价值：记录成功模式、自动推荐 |

---

## 3. 技术可行性验证

### 3.1 Spring AI 1.0.4 Advisor API 支持情况

**验证结果**：

✅ **支持的 Advisor 类型**：
- `ToolCallingAdvisor` - Spring AI 官方提供，用于工具调用编排
- `ChatMemoryAdvisor` - Spring AI 官方提供，用于会话记忆管理
- 自定义 Advisor - 可通过实现 `Advisor` 接口扩展

⚠️ **需要注意**：
- Spring AI 1.0.4 中，`ChatMemoryAdvisor` 是官方推荐方案
- `SessionMemoryAdvisor` 这个名称可能不存在，正确名称是 `ChatMemoryAdvisor`
- `ChatMemoryAdvisor` 需要配合 `ChatMemory` 和 `Saver` 使用

### 3.2 当前依赖分析

**已安装依赖**：
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-client-chat</artifactId>
    <version>1.0.4</version>
</dependency>
```

✅ **包含 Advisor API**：`spring-ai-client-chat` 模块已包含 `Advisor` 接口和实现

### 3.3 需要新增的依赖

**Redis 持久化支持**：
```xml
<!-- 已安装 -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.40.2</version>
</dependency>
```

✅ **Redis 已集成**：项目已使用 Redisson，无需新增依赖

---

## 4. 架构对比

### 4.1 当前架构 vs Hermes 架构

| 维度 | 当前架构 | Hermes 架构 | 差异分析 |
|------|---------|-----------|---------|
| **工具调用** | `ChatClient.defaultToolCallbacks()` | `ToolCallingAdvisor` | 功能等价，只是 API 风格不同 |
| **会话记忆** | 手动传递 `List<Message>` | `ChatMemoryAdvisor` + `ChatMemory` | ✅ 架构更清晰，支持持久化 |
| **窗口控制** | 手动实现三级策略 | `ChatMemory` 内置策略 | ✅ 官方方案，可能更成熟 |
| **技能沉淀** | 无 | `AutoMemoryToolsAdvisor`（需自研） | ❌ 当前无此能力 |
| **代码复杂度** | 低 | 中 | ⚠️ 引入更多抽象层 |

### 4.2 关键发现

⚠️ **重要发现 1**：当前架构已经实现了 Hermes 的核心能力
- `RagAgentService.processWithHistory` 已经实现了会话记忆管理
- 三级窗口控制策略已经相当完善
- 工具调用已经通过 Function Calling 实现

✅ **真正的新增价值**：
1. **Redis 持久化**：当前会话重启后丢失，Hermes 可通过 `ChatMemory` + Redis 持久化
2. **技能沉淀**：`AutoMemoryToolsAdvisor` 记录成功工具调用模式
3. **架构标准化**：使用 Spring AI 官方推荐的 Advisor 模式

---

## 5. 迁移成本评估

### 5.1 代码改动范围

**需要重构的部分**：
1. `RagAgentService` - 重构为基于 Advisor 的模式
2. `SessionMemoryAdvisor` - 新建（或使用官方 `ChatMemoryAdvisor`）
3. `AutoMemoryToolsAdvisor` - 新建（自研）
4. `ChatMemory` 实现 - 集成 Redis 持久化

**保持不变的部分**：
- `AgentToolRegistry` - 工具注册中心
- 所有工具实现（`KnowledgeBaseTool`, `DatabaseQueryTool` 等）
- `ChatController` - API 接口

### 5.2 风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| **破坏现有稳定性** | 高 | ✅ 在独立分支开发，不影响 main 分支 |
| **Advisor API 不成熟** | 中 | 先做技术验证，确认可用性 |
| **学习曲线** | 低 | Spring AI 官方文档齐全 |
| **开发成本** | 中 | 预计 3-5 天完成核心功能 |

---

## 6. 建议方案

### 6.1 推荐方案：**渐进式增强**

**阶段 1：技术验证（1-2 天）**
```bash
# 创建新分支
git checkout -b feature/hermes-agent-poc

# 验证目标：
1. 确认 ChatMemoryAdvisor API 可用性
2. 验证 Redis 持久化集成
3. 编写最小可行性 Demo
```

**阶段 2：核心迁移（2-3 天）**
```
保留现有 RagAgentService
新增 HermesAgentService（基于 Advisor 模式）
两者并存，通过配置切换
```

**阶段 3：技能沉淀（3-5 天）**
```
实现 AutoMemoryToolsAdvisor
记录成功工具调用模式
验证自动推荐效果
```

### 6.2 不推荐方案

❌ **完全重构**：
- 删除 `RagAgentService`
- 完全替换为新的 Hermes 架构
- **风险**：破坏现有稳定性，得不偿失

❌ **维持现状**：
- 当前架构已够用，无需演进
- **风险**：错过技能沉淀等差异化能力

---

## 7. 最终建议

### 7.1 是否有必要转型？

**答案**：**有必要，但采用渐进式增强而非推翻重来**

**理由**：
1. ✅ **真正价值**：Redis 持久化 + 技能沉淀是当前架构缺失的
2. ✅ **架构标准化**：Advisor 模式更符合 Spring AI 官方推荐
3. ⚠️ **成本可控**：通过分支开发 + 并存模式，不影响现有稳定性
4. ❌ **不要为了转型而转型**：当前架构已经实现了核心 Agent 能力

### 7.2 下一步行动

**推荐行动**：
```bash
# 1. 创建技术验证分支
git checkout -b feature/hermes-agent-poc

# 2. 编写技术验证代码
- 验证 ChatMemoryAdvisor API
- 集成 Redis 持久化
- 编写最小可行性 Demo

# 3. 输出验证报告
- API 稳定性评估
- 性能对比测试
- 迁移成本评估

# 4. 基于验证结果决策
- 如果验证成功 → 进入阶段 2（核心迁移）
- 如果验证失败 → 维持现状，聚焦技能沉淀自研
```

---

## 8. 技术验证代码示例

### 8.1 ChatMemoryAdvisor 使用示例

```java
@Configuration
public class HermesAgentConfig {
    
    @Bean
    public ChatMemory chatMemory(RedissonClient redissonClient) {
        // 使用 Redis 持久化会话记忆
        return new RedisChatMemory(redissonClient);
    }
    
    @Bean
    public ChatClient hermesChatClient(
            ChatModel chatModel,
            ToolCallbackProvider toolCallbackProvider,
            ChatMemory chatMemory) {
        
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(toolCallbackProvider)
                .defaultAdvisors(
                    new ChatMemoryAdvisor(chatMemory)  // ← 关键：会话记忆 Advisor
                )
                .build();
    }
}
```

### 8.2 AutoMemoryToolsAdvisor 设计思路

```java
@Component
public class AutoMemoryToolsAdvisor implements Advisor {
    
    private final ToolCallPatternRepository patternRepo;
    
    @Override
    public AdvisedResponse advise(AdvisedRequest request) {
        // 1. 记录当前工具调用模式
        recordToolCallPattern(request);
        
        // 2. 检测相似历史模式
        List<ToolCallPattern> similarPatterns = 
            patternRepo.findSimilarPatterns(request);
        
        // 3. 自动推荐工具（如果匹配度高）
        if (similarPatterns.isNotEmpty()) {
            return autoRecommendTool(similarPatterns.get(0));
        }
        
        // 4. 否则，正常处理
        return proceedWithNormalFlow(request);
    }
}
```

---

## 9. 总结

**核心结论**：
1. ✅ **当前架构已经实现了 Hermes 的核心能力**（Agent Loop + 工具编排 + 窗口控制）
2. ✅ **真正的新增价值**：Redis 持久化 + 技能沉淀
3. ✅ **建议采用渐进式增强**：分支开发 + 并存模式 + 逐步迁移
4. ❌ **不要推翻重来**：当前代码已经很稳定，破坏性重构得不偿失

**下一步**：
创建 `feature/hermes-agent-poc` 分支，进行技术验证，基于验证结果决策是否继续推进。

---

**文档版本**: 1.0  
**状态**: 待评审  
**审批**: 待用户确认
