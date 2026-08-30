# Agent 系统 LLM 调用优化设计文档

**创建日期**: 2026-08-30  
**作者**: AI Assistant  
**状态**: 待评审  
**版本**: v1.0

---

## 1. 概述

### 1.1 问题背景

在使用 Agent 系统执行"生成 API 文档并保存到文件"这类任务时，出现 `java.net.SocketTimeoutException: Read timed out` 错误。日志分析显示：

- **实际耗时**: 126889ms（约 127 秒）
- **超时阈值**: 120s（`spring.http.client.read-timeout` 配置）
- **触发点**: `OpenAiApi.chatCompletionEntity()` 非流式调用
- **根本原因**: 慢模型 + 大输出 token 数 + 非流式调用 + Docker 网络开销

### 1.2 优化目标

**P0（止血）**: 改为流式调用，避免读超时  
**P1（治本）**: 写文件逻辑下沉到工具内部，减少 LLM 输出 token 数  
**P2（稳健性）**: 智能重试 + 上下文裁剪，提升系统稳定性

### 1.3 设计原则

1. **通用性**: 优化面向整个 Agent 系统，不仅限于特定场景
2. **向后兼容**: 保留非流式调用作为降级选项
3. **渐进式**: 分阶段实施，每个阶段独立可测试
4. **可观测性**: 增加关键指标监控（耗时、token 数、错误类型）

---

## 2. 架构设计

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                    Agent Layer                          │
│  ┌─────────────────────────────────────────────────────┐│
│  │           RagAgentService (入口)                    ││
│  │  - processWithHistory()                             ││
│  │  - process()                                        ││
│  └─────────────────────────────────────────────────────┘│
│                          │                              │
│                          ▼                              │
│  ┌─────────────────────────────────────────────────────┐│
│  │         StreamingAgentExecutor (新增)               ││
│  │  - executeStreaming()  ← 流式调用                  ││
│  │  - executeBlocking()     ← 非流式调用 (降级)       ││
│  │  - consumeStream()       ← 内部消费流              ││
│  └─────────────────────────────────────────────────────┘│
│                          │                              │
│                          ▼                              │
│  ┌─────────────────────────────────────────────────────┐│
│  │         SmartRetryHandler (新增)                    ││
│  │  - shouldRetry()         ← 智能判断是否重试        ││
│  │  - getRetryBackoff()     ← 退避策略                ││
│  └─────────────────────────────────────────────────────┘│
│                          │                              │
│                          ▼                              │
│  ┌─────────────────────────────────────────────────────┐│
│  │         ContextPruner (新增)                        ││
│  │  - pruneBySemantic()     ← 语义裁剪                ││
│  │  - pruneByTimeWindow()   ← 时间窗口裁剪            ││
│  └─────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    Tool Layer                           │
│  ┌─────────────────────────────────────────────────────┐│
│  │  file-manager Skill (增强)                          ││
│  │  - write-large-content  ← 大文件写入 (新增)        ││
│  │  - write                ← 普通写入 (现有)          ││
│  └─────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────┘
```

### 2.2 调用链路对比

**优化前**:
```
用户请求 → RagAgentService → chatModel.call() → LLM API (非流式)
                                              ↓
                                    等待完整响应 (127s)
                                              ↓
                                    SocketTimeoutException
```

**优化后 (P0)**:
```
用户请求 → RagAgentService → StreamingAgentExecutor
                                    ↓
                           chatModel.stream() → LLM API (流式)
                                    ↓
                           实时消费流 (首 token < 5s)
                                    ↓
                           累积完整响应 → 返回
```

**优化后 (P1)**:
```
用户请求 → RagAgentService → LLM 思考 → 调用 file-manager.write-large-content
                                    ↓
                           LLM 只传递：{filename, content}
                                    ↓
                           file-manager 内部判断内容大小
                                    ↓
                           超过阈值 → 直接落盘，不返回内容给 LLM
                                    ↓
                           LLM 收到：{success: true, file_path: "..."}
```

---

## 3. 详细设计

### 3.1 P0: 流式调用支持

#### 3.1.1 新增类：`StreamingAgentExecutor`

**位置**: `company-rag-agent/src/main/java/com/company/rag/agent/executor/StreamingAgentExecutor.java`

**职责**:
- 封装 Spring AI 的流式调用 API
- 内部消费流，累积完整响应
- 支持流式与非流式的切换（配置驱动）
- 实时输出 Agent 思考过程（可选）

**核心方法**:
```java
public class StreamingAgentExecutor {
    
    private final ChatModel chatModel;
    private final boolean streamingEnabled;  // 配置项
    private final boolean streamThoughts;    // 配置项
    
    /**
     * 执行 Agent 调用（自动选择流式/非流式）
     */
    public AgentResult execute(ChatRequest request);
    
    /**
     * 流式执行
     */
    private AgentResult executeStreaming(ChatRequest request);
    
    /**
     * 非流式执行（降级）
     */
    private AgentResult executeBlocking(ChatRequest request);
    
    /**
     * 内部消费流，累积响应
     */
    private Flux<ChatResponse> consumeStream(Flux<ChatResponse> responseFlux);
}
```

**配置项** (application.yml):
```yaml
agent:
  streaming:
    enabled: true              # 是否启用流式调用
    fallback-to-blocking: true # 流式失败时降级到非流式
    stream-thoughts: true      # 是否流式输出思考过程
```

**流式消费逻辑**:
```java
private AgentResult consumeStream(Flux<ChatResponse> responseFlux) {
    StringBuilder fullResponse = new StringBuilder();
    List<ToolCall> toolCalls = new ArrayList<>();
    
    responseFlux.doOnNext(response -> {
        // 实时输出思考过程（如果启用）
        if (streamThoughts && response.hasThought()) {
            log.info("[AGENT-THOUGHT] {}", response.getThought());
        }
        
        // 累积响应
        if (response.hasContent()) {
            fullResponse.append(response.getContent());
        }
        
        // 收集工具调用
        if (response.hasToolCalls()) {
            toolCalls.addAll(response.getToolCalls());
        }
    })
    .blockLast(); // 等待流完成
    
    return new AgentResult(fullResponse.toString(), toolCalls);
}
```

#### 3.1.2 修改类：`RagAgentService`

**位置**: `company-rag-agent/src/main/java/com/company/rag/agent/service/RagAgentService.java`

**改动**:
- 注入 `StreamingAgentExecutor`
- `processWithHistory()` 方法改用 `StreamingAgentExecutor.execute()`
- 保留原有 `AGENT_TIMEOUT_MINUTES` 配置（作为兜底）

**改动代码**:
```java
@Service
public class RagAgentService {
    
    // 新增注入
    private final StreamingAgentExecutor streamingExecutor;
    
    // 修改 processWithHistory 方法
    public AgentResult processWithHistory(String userMessage, List<Message> history) {
        // 构建请求
        ChatRequest request = buildChatRequest(userMessage, history);
        
        // 使用流式执行器
        return streamingExecutor.execute(request);
    }
}
```

---

### 3.2 P1: 写文件逻辑下沉到工具内部

#### 3.2.1 修改 Skill：`file-manager`

**位置**: `agent_skills/file-manager/SKILL.md` 和 `agent_skills/file-manager/scripts/file_manager.py`

**新增操作**: `write-large-content`

**SKILL.md 改动**:
```markdown
## Supported Operations (新增)

| Operation | Description | Example |
|-----------|-------------|---------|
| `write-large-content` | 写入大文件内容（自动判断大小，超过阈值直接落盘） | `write-large-content --file "api-doc.md" --content "..."` |

## write-large-content 操作说明

**触发条件**: 
- 内容大小 > 10KB（可配置）
- 或明确指定 `--force-large` 参数

**行为**:
1. 判断内容大小
2. 如果超过阈值：
   - 直接写入文件
   - 返回 `{success: true, file_path: "...", size_bytes: 12345}`
   - **不返回文件内容给 LLM**
3. 如果未超过阈值：
   - 退化为普通 `write` 操作
   - 返回完整响应（包含内容预览）

**参数**:
- `--file` (必需): 目标文件路径
- `--content` (必需): 文件内容
- `--size-threshold` (可选): 大小阈值（字节），默认 10240 (10KB)
- `--encoding` (可选): 编码，默认 utf-8
- `--force-large` (可选): 强制按大文件处理
```

**file_manager.py 改动**:
```python
def write_large_content(args):
    """写入大文件内容，自动判断大小"""
    file_path = args.file
    content = args.content
    size_threshold = getattr(args, 'size_threshold', 10240)  # 默认 10KB
    
    content_size = len(content.encode('utf-8'))
    
    if content_size > size_threshold:
        # 大文件处理：直接落盘，不返回内容
        log.info(f"检测到大文件内容 ({content_size} bytes)，直接落盘")
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        
        return {
            "success": True,
            "message": "大文件内容已写入",
            "file_path": os.path.abspath(file_path),
            "size_bytes": content_size,
            "large_file": True  # 标记，告知 LLM 不要期望返回内容
        }
    else:
        # 小文件：退化为普通 write
        log.info(f"内容较小 ({content_size} bytes)，使用普通写入")
        return write_file(args)  # 调用现有 write 函数
```

#### 3.2.2 修改 Agent System Prompt

**位置**: `company-rag-agent/src/main/java/com/company/rag/agent/config/AgentConfig.java`

**改动**:
在 system prompt 中增加写大文件的指导：

```markdown
## 文件写入最佳实践

当你需要写入大文件（如 API 文档、报告、代码文件等）时：

1. **优先使用 `write-large-content` 操作**（而不是普通 `write`）
   - 适用场景：内容预计超过 10KB
   - 优势：避免大段内容返回给 LLM，减少 token 消耗和超时风险

2. **调用方式**:
   ```
   write-large-content --file "目标文件路径" --content "文件内容"
   ```

3. **返回值说明**:
   - 成功时返回：`{success: true, file_path: "...", size_bytes: 12345}`
   - **不会返回文件内容**（这是设计，不是错误）
   - 你只需要确认写入成功即可，无需重复读取或验证

4. **不要做的事情**:
   - ❌ 不要尝试读取刚写入的大文件来"验证"内容
   - ❌ 不要将大文件内容再次输出到响应中
   - ❌ 不要对同一个文件重复调用 write-large-content（除非用户明确要求）
```

---

### 3.3 P2: 稳健性优化

#### 3.3.1 新增类：`SmartRetryHandler`

**位置**: `company-rag-agent/src/main/java/com/company/rag/agent/retry/SmartRetryHandler.java`

**职责**:
- 根据错误类型智能判断是否重试
- 超时错误不重试
- 网络错误可重试（有限次数）

**核心逻辑**:
```java
@Component
public class SmartRetryHandler {
    
    private static final int MAX_RETRY_COUNT = 1;
    private static final Set<Class<? extends Throwable>> RETRYABLE_ERRORS = Set.of(
        ConnectException.class,      // 连接错误
        UnknownHostException.class,  // DNS 解析失败
        SocketException.class        // 套接字异常（非超时）
    );
    
    private static final Set<Class<? extends Throwable>> NON_RETRYABLE_ERRORS = Set.of(
        SocketTimeoutException.class,     // 读超时
        InterruptedIOException.class,     // IO 中断
        ResourceAccessException.class     // 资源访问异常（通常包含超时）
    );
    
    /**
     * 判断是否应该重试
     */
    public boolean shouldRetry(Throwable ex, int retryCount) {
        if (retryCount >= MAX_RETRY_COUNT) {
            return false;
        }
        
        // 超时错误：永不重试
        if (isTimeoutError(ex)) {
            log.warn("检测到超时错误，不重试 | error={}", ex.getClass().getSimpleName());
            return false;
        }
        
        // 网络错误：可重试
        if (isRetryableError(ex)) {
            log.info("检测到可重试的网络错误，准备重试 | error={}, retryCount={}", 
                    ex.getClass().getSimpleName(), retryCount);
            return true;
        }
        
        // 其他错误：不重试
        log.warn("检测到不可重试的错误 | error={}", ex.getClass().getSimpleName());
        return false;
    }
    
    private boolean isTimeoutError(Throwable ex) {
        return NON_RETRYABLE_ERRORS.stream()
            .anyMatch(errorClass -> errorClass.isInstance(ex) || 
                                   isCausedBy(ex, errorClass));
    }
    
    private boolean isRetryableError(Throwable ex) {
        return RETRYABLE_ERRORS.stream()
            .anyMatch(errorClass -> errorClass.isInstance(ex) || 
                                   isCausedBy(ex, errorClass));
    }
    
    private boolean isCausedBy(Throwable ex, Class<? extends Throwable> causeClass) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (causeClass.isInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
```

**集成到 Spring AI**:
```java
@Configuration
public class AgentRetryConfig {
    
    @Bean
    public RetryTemplate agentRetryTemplate(SmartRetryHandler smartRetryHandler) {
        return RetryTemplate.builder()
            .maxAttempts(2) // 1 次重试
            .customExponentialBackoff(100, 2000, 2.0) // 退避：100ms -> 2000ms
            .retryOn(exception -> smartRetryHandler.shouldRetry(exception, 0))
            .build();
    }
}
```

#### 3.3.2 新增类：`ContextPruner`

**位置**: `company-rag-agent/src/main/java/com/company/rag/agent/context/ContextPruner.java`

**职责**:
- 语义裁剪：保留与当前问题相关的历史对话
- 时间窗口裁剪：保留最近 3 分钟内的对话
- Token 预算控制（可选）

**核心逻辑**:
```java
@Component
public class ContextPruner {
    
    private static final Duration TIME_WINDOW = Duration.ofMinutes(3);
    private static final int MAX_CONTEXT_MESSAGES = 20; // 最大消息数
    
    private final EmbeddingModel embeddingModel; // 用于语义相似度计算
    
    /**
     * 裁剪对话历史
     * 
     * @param currentMessage 当前用户消息
     * @param history 历史对话
     * @return 裁剪后的历史对话
     */
    public List<Message> prune(String currentMessage, List<Message> history) {
        if (history.isEmpty()) {
            return history;
        }
        
        // 步骤 1: 时间窗口裁剪
        List<Message> timeFiltered = filterByTimeWindow(history);
        
        // 步骤 2: 语义相关性裁剪
        List<Message> semanticFiltered = filterBySemanticRelevance(
            currentMessage, timeFiltered
        );
        
        // 步骤 3: 数量限制
        if (semanticFiltered.size() > MAX_CONTEXT_MESSAGES) {
            return semanticFiltered.subList(
                semanticFiltered.size() - MAX_CONTEXT_MESSAGES,
                semanticFiltered.size()
            );
        }
        
        return semanticFiltered;
    }
    
    /**
     * 时间窗口过滤：保留最近 3 分钟内的对话
     */
    private List<Message> filterByTimeWindow(List<Message> history) {
        Instant cutoff = Instant.now().minus(TIME_WINDOW);
        
        return history.stream()
            .filter(msg -> msg.getTimestamp() == null || 
                          msg.getTimestamp().isAfter(cutoff))
            .collect(Collectors.toList());
    }
    
    /**
     * 语义相关性过滤：保留与当前问题相关的历史
     */
    private List<Message> filterBySemanticRelevance(
        String currentMessage, 
        List<Message> history
    ) {
        if (history.size() <= 5) {
            return history; // 消息太少，全部保留
        }
        
        // 计算当前消息的嵌入向量
        float[] currentEmbedding = embeddingModel.embed(currentMessage);
        
        // 计算每条历史消息的相关性分数
        List<ScoredMessage> scoredMessages = history.stream()
            .map(msg -> {
                float[] historyEmbedding = embeddingModel.embed(msg.getContent());
                float similarity = cosineSimilarity(currentEmbedding, historyEmbedding);
                return new ScoredMessage(msg, similarity);
            })
            .sorted(Comparator.comparingDouble(ScoredMessage::score).reversed())
            .collect(Collectors.toList());
        
        // 保留相关性分数 > 0.6 的消息
        return scoredMessages.stream()
            .filter(sm -> sm.score() > 0.6)
            .map(ScoredMessage::message)
            .collect(Collectors.toList());
    }
    
    private float cosineSimilarity(float[] a, float[] b) {
        // 余弦相似度计算
        float dotProduct = 0.0f;
        float normA = 0.0f;
        float normB = 0.0f;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        return dotProduct / (float) Math.sqrt(normA * normB);
    }
    
    record ScoredMessage(Message message, double score) {}
}
```

**集成到 RagAgentService**:
```java
@Service
public class RagAgentService {
    
    private final ContextPruner contextPruner;
    
    public AgentResult processWithHistory(String userMessage, List<Message> history) {
        // 裁剪历史对话
        List<Message> prunedHistory = contextPruner.prune(userMessage, history);
        
        log.info("上下文裁剪 | 原始消息数={}, 裁剪后消息数={}", 
                history.size(), prunedHistory.size());
        
        // 使用裁剪后的历史
        return streamingExecutor.execute(userMessage, prunedHistory);
    }
}
```

---

## 4. 配置设计

### 4.1 新增配置项

**位置**: `company-rag-bootstrap/src/main/resources/application.yml`

```yaml
agent:
  # 流式调用配置
  streaming:
    enabled: true              # 是否启用流式调用
    fallback-to-blocking: true # 流式失败时降级到非流式
    stream-thoughts: true      # 是否流式输出思考过程
  
  # 重试策略配置
  retry:
    smart-enabled: true        # 是否启用智能重试
    max-attempts: 2            # 最大尝试次数（1 次重试）
    initial-interval: 100ms    # 初始退避间隔
    max-interval: 2s           # 最大退避间隔
    multiplier: 2.0            # 退避倍数
  
  # 上下文裁剪配置
  context:
    time-window: 3m            # 时间窗口（3 分钟）
    max-messages: 20           # 最大消息数
    semantic-threshold: 0.6    # 语义相关性阈值
    enabled: true              # 是否启用裁剪
  
  # 大文件写入配置
  file-write:
    size-threshold: 10240      # 大文件阈值（字节），默认 10KB
    enabled: true              # 是否启用大文件优化
```

---

## 5. 错误处理

### 5.1 流式调用错误处理

| 错误类型 | 处理方式 |
|---------|---------|
| 流式调用超时 | 降级到非流式调用（如果 `fallback-to-blocking=true`） |
| 流式调用失败（网络错误） | 重试 1 次，仍失败则降级到非流式 |
| 非流式调用也失败 | 返回错误给 Controller，不继续重试 |

### 5.2 大文件写入错误处理

| 错误类型 | 处理方式 |
|---------|---------|
| 文件路径不存在 | 自动创建父目录，仍失败则返回错误 |
| 磁盘空间不足 | 返回错误，不重试 |
| 权限不足 | 返回错误，不重试 |
| 编码错误 | 尝试 GBK 编码，仍失败则返回错误 |

### 5.3 上下文裁剪错误处理

| 错误类型 | 处理方式 |
|---------|---------|
| Embedding 模型调用失败 | 退化为时间窗口裁剪（不做语义裁剪） |
| 历史消息无时间戳 | 保留该消息（保守策略） |
| 裁剪后消息数为 0 | 保留最近 1 条消息（避免空上下文） |

---

## 6. 测试策略

### 6.1 单元测试

**StreamingAgentExecutorTest**:
- 测试流式调用成功场景
- 测试流式调用失败降级到非流式
- 测试思考过程流式输出
- 测试响应累积正确性

**SmartRetryHandlerTest**:
- 测试超时错误不重试
- 测试网络错误可重试
- 测试达到最大重试次数后停止

**ContextPrunerTest**:
- 测试时间窗口裁剪（3 分钟外的消息被丢弃）
- 测试语义相关性裁剪（低相关性消息被丢弃）
- 测试边界场景（空历史、单条消息等）

**FileManagerSkillTest**:
- 测试小文件走普通 write
- 测试大文件走 write-large-content
- 测试阈值边界（10KB 左右）

### 6.2 集成测试

**AgentIntegrationTest**:
- 测试完整调用链路（用户请求 → Agent → 流式调用 → 返回）
- 测试大文件写入场景（生成 API 文档并保存）
- 测试多轮对话上下文裁剪

### 6.3 性能测试

**压测场景**:
- 并发 10 个 Agent 调用，验证流式调用不超时
- 生成 100KB 大文件，验证 token 消耗降低
- 长对话（50 轮）后验证上下文裁剪生效

---

## 7. 监控与可观测性

### 7.1 新增指标

```java
// Micrometer 指标
private final Counter streamingCallsCounter;      // 流式调用次数
private final Counter blockingCallsCounter;       // 非流式调用次数
private final Counter retryCounter;               // 重试次数
private final Timer callDurationTimer;            // 调用耗时
private final DistributionSummary tokenCounter;   // Token 消耗
private final Counter largeFileWriteCounter;      // 大文件写入次数
```

### 7.2 日志增强

**关键日志点**:
```
[AGENT-STREAM] 启用流式调用 | traceId=xxx
[AGENT-BLOCKING] 降级到非流式调用 | traceId=xxx, reason=xxx
[AGENT-THOUGHT] 实时输出思考过程 | traceId=xxx, thought=xxx
[RETRY] 触发重试 | traceId=xxx, error=xxx, retryCount=1
[CONTEXT-PRUNE] 上下文裁剪 | traceId=xxx, before=50, after=15, timeFiltered=20, semanticFiltered=15
[FILE-WRITE] 大文件写入 | traceId=xxx, file=xxx, size=12345, threshold=10240
```

---

## 8. 实施计划

### 阶段 1 (P0): 流式调用支持

**任务**:
1. 创建 `StreamingAgentExecutor` 类
2. 修改 `RagAgentService` 使用流式执行器
3. 添加配置项到 `application.yml`
4. 编写单元测试
5. 验证超时问题是否解决

**预计时间**: 2-3 小时

**验收标准**:
- ✅ 生成 API 文档不再超时
- ✅ 首 token 返回时间 < 5 秒
- ✅ 配置 `streaming.enabled=false` 可降级到非流式

---

### 阶段 2 (P1): 写文件逻辑下沉

**任务**:
1. 修改 `file_manager.py` 增加 `write-large-content` 操作
2. 修改 `SKILL.md` 文档说明
3. 修改 `AgentConfig.java` 更新 system prompt
4. 编写集成测试（生成大文件场景）
5. 验证 token 消耗是否降低

**预计时间**: 2-3 小时

**验收标准**:
- ✅ 大文件（>10KB）写入时 LLM 输出 token 数减少 80% 以上
- ✅ 文件正确落盘
- ✅ LLM 不再返回完整文件内容

---

### 阶段 3 (P2): 稳健性优化

**任务**:
1. 创建 `SmartRetryHandler` 类
2. 创建 `ContextPruner` 类
3. 集成到 `RagAgentService`
4. 配置 Spring AI RetryTemplate
5. 编写单元测试
6. 验证重试策略和上下文裁剪

**预计时间**: 3-4 小时

**验收标准**:
- ✅ 超时错误不触发重试
- ✅ 网络错误重试 1 次
- ✅ 3 分钟外的对话被裁剪
- ✅ 低相关性对话被裁剪

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|-----|------|---------|
| 流式调用兼容性 | 某些 LLM 供应商不支持流式 | 保留非流式降级开关 |
| 语义裁剪准确性 | 可能误删相关对话 | 阈值可调（默认 0.6），保守策略 |
| 大文件阈值设置 | 阈值过高/过低影响效果 | 配置化（默认 10KB），可动态调整 |
| 重试策略过于激进 | 可能加重系统负担 | 限制最大 1 次重试，超时不重试 |
| 代码改动范围大 | 引入新 bug | 分阶段实施，每阶段独立测试 |

---

## 10. 向后兼容性

### 10.1 API 兼容

- `RagAgentService.process()` 方法签名不变
- `AgentResult` 返回格式不变
- Controller 层无需改动

### 10.2 配置兼容

- 所有新增配置项都有默认值
- 旧配置项保持有效（如 `AGENT_TIMEOUT_MINUTES`）
- 可通过配置快速回退到优化前行为

### 10.3 技能兼容

- 现有 `write` 操作保持不变
- 新增 `write-large-content` 操作
- LLM 可选择使用哪个操作

---

## 11. 总结

本设计通过三个阶段逐步优化 Agent 系统的 LLM 调用：

1. **P0 流式调用**: 快速解决超时问题，首 token 快速返回
2. **P1 写文件下沉**: 从根本上减少 token 消耗，避免大输出
3. **P2 稳健性优化**: 智能重试 + 上下文裁剪，提升长期稳定性

设计遵循通用性、向后兼容、渐进式实施原则，每个阶段独立可测试，风险可控。

---

## 附录 A: 关键代码示例

### A.1 StreamingAgentExecutor 完整实现

```java
package com.company.rag.agent.executor;

import com.company.rag.agent.service.AgentResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Slf4j
@Component
@RequiredArgsConstructor
public class StreamingAgentExecutor {
    
    private final ChatModel chatModel;
    private final boolean streamingEnabled = true; // 从配置读取
    private final boolean fallbackToBlocking = true; // 从配置读取
    private final boolean streamThoughts = true; // 从配置读取
    
    public AgentResult execute(Prompt prompt) {
        if (streamingEnabled) {
            try {
                return executeStreaming(prompt);
            } catch (Exception e) {
                log.warn("流式调用失败，降级到非流式 | error={}", e.getMessage());
                if (fallbackToBlocking) {
                    return executeBlocking(prompt);
                }
                throw e;
            }
        } else {
            return executeBlocking(prompt);
        }
    }
    
    private AgentResult executeStreaming(Prompt prompt) {
        log.info("[AGENT-STREAM] 启用流式调用");
        
        Flux<ChatResponse> responseFlux = chatModel.stream(prompt);
        
        return consumeStream(responseFlux);
    }
    
    private AgentResult executeBlocking(Prompt prompt) {
        log.info("[AGENT-BLOCKING] 使用非流式调用");
        
        ChatResponse response = chatModel.call(prompt);
        
        return convertToAgentResult(response);
    }
    
    private AgentResult consumeStream(Flux<ChatResponse> responseFlux) {
        StringBuilder fullResponse = new StringBuilder();
        
        responseFlux.doOnNext(response -> {
            String content = response.getResult().getOutput().getText();
            
            // 实时输出思考过程
            if (streamThoughts && response.getMetadata() != null) {
                String thought = (String) response.getMetadata().get("thought");
                if (thought != null) {
                    log.info("[AGENT-THOUGHT] {}", thought);
                }
            }
            
            fullResponse.append(content);
        })
        .blockLast();
        
        return new AgentResult(fullResponse.toString(), null);
    }
    
    private AgentResult convertToAgentResult(ChatResponse response) {
        String content = response.getResult().getOutput().getText();
        return new AgentResult(content, null);
    }
}
```

### A.2 file_manager.py write-large-content 实现

```python
import argparse
import json
import logging
import os
from pathlib import Path

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

def write_large_content(args):
    """写入大文件内容，自动判断大小"""
    file_path = args.file
    content = args.content
    size_threshold = getattr(args, 'size_threshold', 10240)  # 默认 10KB
    encoding = getattr(args, 'encoding', 'utf-8')
    
    try:
        # 计算内容大小（字节）
        content_size = len(content.encode(encoding))
        
        logger.info(f"准备写入文件 | file={file_path}, size={content_size} bytes, threshold={size_threshold}")
        
        # 判断是否为大文件
        is_large_file = content_size > size_threshold or getattr(args, 'force_large', False)
        
        if is_large_file:
            # 大文件处理：直接落盘，不返回内容
            logger.info(f"检测到大文件内容 ({content_size} bytes)，直接落盘")
            
            # 创建父目录（如果不存在）
            Path(file_path).parent.mkdir(parents=True, exist_ok=True)
            
            # 写入文件
            with open(file_path, 'w', encoding=encoding) as f:
                f.write(content)
            
            return {
                "success": True,
                "message": "大文件内容已写入",
                "file_path": os.path.abspath(file_path),
                "size_bytes": content_size,
                "large_file": True
            }
        else:
            # 小文件：退化为普通 write
            logger.info(f"内容较小 ({content_size} bytes)，使用普通写入")
            return write_file(args)
    
    except Exception as e:
        logger.error(f"写入文件失败 | file={file_path}, error={str(e)}")
        return {
            "success": False,
            "error": str(e)
        }

def write_file(args):
    """普通文件写入"""
    file_path = args.file
    content = args.content
    encoding = getattr(args, 'encoding', 'utf-8')
    overwrite = getattr(args, 'overwrite', True)
    
    try:
        # 检查文件是否已存在
        if os.path.exists(file_path) and not overwrite:
            return {
                "success": False,
                "error": f"文件已存在：{file_path}"
            }
        
        # 创建父目录
        Path(file_path).parent.mkdir(parents=True, exist_ok=True)
        
        # 写入文件
        with open(file_path, 'w', encoding=encoding) as f:
            f.write(content)
        
        return {
            "success": True,
            "message": "文件写入成功",
            "file_path": os.path.abspath(file_path),
            "size_bytes": len(content.encode(encoding)),
            "preview": content[:200] + "..." if len(content) > 200 else content
        }
    
    except Exception as e:
        logger.error(f"写入文件失败 | file={file_path}, error={str(e)}")
        return {
            "success": False,
            "error": str(e)
        }

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="File Manager")
    subparsers = parser.add_subparsers(dest='operation', help='操作类型')
    
    # write-large-content 命令
    write_large_parser = subparsers.add_parser('write-large-content', help='写入大文件内容')
    write_large_parser.add_argument('--file', required=True, help='文件路径')
    write_large_parser.add_argument('--content', required=True, help='文件内容')
    write_large_parser.add_argument('--size-threshold', type=int, default=10240, help='大文件阈值（字节）')
    write_large_parser.add_argument('--encoding', default='utf-8', help='文件编码')
    write_large_parser.add_argument('--force-large', action='store_true', help='强制按大文件处理')
    write_large_parser.set_defaults(func=write_large_content)
    
    # write 命令
    write_parser = subparsers.add_parser('write', help='写入文件')
    write_parser.add_argument('--file', required=True, help='文件路径')
    write_parser.add_argument('--content', required=True, help='文件内容')
    write_parser.add_argument('--encoding', default='utf-8', help='文件编码')
    write_parser.add_argument('--overwrite', type=bool, default=True, help='是否覆盖')
    write_parser.set_defaults(func=write_file)
    
    args = parser.parse_args()
    
    if hasattr(args, 'func'):
        result = args.func(args)
        print(json.dumps(result, ensure_ascii=False))
    else:
        parser.print_help()
```

---

## 附录 B: 配置示例

### B.1 application.yml 完整配置

```yaml
spring:
  ai:
    # ... 其他 AI 配置
    
agent:
  # 流式调用配置
  streaming:
    enabled: true              # 是否启用流式调用
    fallback-to-blocking: true # 流式失败时降级到非流式
    stream-thoughts: true      # 是否流式输出思考过程
  
  # 重试策略配置
  retry:
    smart-enabled: true        # 是否启用智能重试
    max-attempts: 2            # 最大尝试次数（1 次重试）
    initial-interval: 100ms    # 初始退避间隔
    max-interval: 2s           # 最大退避间隔
    multiplier: 2.0            # 退避倍数
  
  # 上下文裁剪配置
  context:
    time-window: 3m            # 时间窗口（3 分钟）
    max-messages: 20           # 最大消息数
    semantic-threshold: 0.6    # 语义相关性阈值
    enabled: true              # 是否启用裁剪
  
  # 大文件写入配置
  file-write:
    size-threshold: 10240      # 大文件阈值（字节），默认 10KB
    enabled: true              # 是否启用大文件优化
  
  # Agent 超时配置（兜底）
  timeout-minutes: 5           # Agent 整体超时（分钟）
```

---

**文档结束**
