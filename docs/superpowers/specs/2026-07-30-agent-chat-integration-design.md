# Agent Chat 集成与智能路由系统设计文档

**创建日期**: 2026-07-30  
**状态**: 待审批  
**作者**: CompanyRag Team  

---

## 1. 概述

### 1.1 设计目标

将现有的 `AgentController` 接口与 `RagController` 整合，构建统一的智能问答系统，实现：
- 用户无感知的智能路由（自动判断使用 RAG 还是 Agent）
- 企业级的高可用性和降级能力
- 简洁的前端接口和灵活的后端扩展

### 1.2 背景

当前系统存在两个独立的聊天入口：
- `/api/rag/search` - RAG 文档检索问答
- `/api/agent/chat` - Agent 工具调用（未使用）

问题：
- 用户需要理解两种模式的区别，使用门槛高
- 前端需要维护两套调用逻辑
- 无法根据问题类型自动选择最优方案

### 1.3 范围

本设计涵盖：
- 后端：统一路由层、意图识别、降级策略
- 前端：统一接口调用、高级模式切换
- 不包含：现有 RAG 和 Agent 内部逻辑的大规模重构

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                        前端                              │
│  ┌─────────────────────────────────────────────────┐    │
│  │  统一调用 /api/chat                             │    │
│  │  可选参数：includeDebug, sessionId, mode        │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                   ChatController                        │
│  (新增，统一入口)                                       │
│  POST /api/chat                                         │
└─────────────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────────────┐
│                   ChatRouter                            │
│  (新增，核心路由层)                                      │
│  1. 意图识别（混合策略：规则 + LLM）                    │
│  2. 路由决策                                             │
│  3. 降级处理                                             │
└─────────────────────────────────────────────────────────┘
                    ↓                    ↓
        ┌─────────────────┐    ┌─────────────────┐
        │   RAG 处理器     │    │   Agent 处理器   │
        │ (现有 RagService)│    │(现有 RagAgentService)│
        └─────────────────┘    └─────────────────┘
                    ↓                    ↓
        ┌─────────────────┐    ┌─────────────────┐
        │ 向量检索         │    │ 工具调用         │
        │ Rerank          │    │ - database_query │
        │ 文档摘要         │    │ - code_search    │
        └─────────────────┘    │ - api_doc        │
                               └─────────────────┘
```

---

## 3. 核心组件设计

### 3.1 ChatController（新增）

**位置**: `company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java`

**职责**: 统一聊天入口，接收前端请求并委托给 ChatRouter 处理

```java
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {
    
    private final ChatRouter chatRouter;
    
    /**
     * 统一聊天接口
     * POST /api/chat
     */
    @PostMapping("/chat")
    public R<ChatResponse> chat(@RequestBody ChatRequest request) {
        return R.ok(chatRouter.route(request));
    }
}
```

### 3.2 ChatRouter（新增）

**位置**: `company-rag-rag/src/main/java/com/company/rag/rag/router/ChatRouter.java`

**职责**: 意图识别、路由决策、降级处理

```java
@Service
@RequiredArgsConstructor
public class ChatRouter {
    
    private final IntentRecognizer intentRecognizer;
    private final RagService ragService;
    private final RagAgentService agentService;
    
    /**
     * 路由主入口
     */
    public ChatResponse route(ChatRequest request) {
        // P1: 意图识别（失败降级到默认）
        IntentType intent = recognizeIntentSafely(request.getQuery());
        
        // P0: 核心处理（失败降级到兜底回答）
        try {
            return processByIntent(intent, request);
        } catch (Exception e) {
            log.error("路由处理失败，使用兜底回答", e);
            return ChatResponse.fallback("抱歉，系统繁忙，请稍后重试。");
        }
    }
    
    private ChatResponse processByIntent(IntentType intent, ChatRequest request) {
        return switch (intent) {
            case DOCUMENT -> ragService.searchAndAnswer(request);
            case DATABASE, CODE -> agentService.process(request.getQuery(), null);
            case CHAT -> directLLMAnswer(request.getQuery());
        };
    }
}
```

### 3.3 IntentRecognizer（新增）

**位置**: `company-rag-rag/src/main/java/com/company/rag/rag/router/IntentRecognizer.java`

**职责**: 混合策略意图识别（规则 + LLM）

```java
@Service
@RequiredArgsConstructor
public class IntentRecognizer {
    
    private final OpenAiChatModel chatModel;
    
    // 规则匹配（高置信度场景）
    private static final List<PatternRule> RULES = List.of(
        PatternRule.builder()
            .intent(IntentType.DATABASE)
            .patterns(".*(多少 | 统计 | 数量 | 汇总 | 平均).*",
                      ".*(查询 | 查找).*(数据 | 记录 | 用户 | 订单).*")
            .confidence(0.95)
            .build(),
        PatternRule.builder()
            .intent(IntentType.CODE)
            .patterns(".*(接口 | 类 | 方法 | 代码 | 实现).*",
                      ".*(怎么 | 如何).*(实现 | 写).*")
            .confidence(0.95)
            .build(),
        PatternRule.builder()
            .intent(IntentType.CHAT)
            .patterns(".*(你好 | 谢谢 | 再见).*",
                      ".*(你 (是 | 叫 | 能).*)|(谁 | 什么).*")
            .confidence(0.90)
            .build()
    );
    
    /**
     * 混合策略意图识别
     */
    public IntentResult recognize(String query) {
        // 1. 优先规则匹配
        for (PatternRule rule : RULES) {
            if (rule.matches(query)) {
                if (rule.getConfidence() >= 0.8) {
                    return IntentResult.success(rule.getIntent(), "RULE");
                }
            }
        }
        
        // 2. 规则匹配失败，降级到 LLM
        try {
            return recognizeByLLM(query);
        } catch (Exception e) {
            log.warn("LLM 意图识别失败，使用默认", e);
            return IntentResult.success(IntentType.DOCUMENT, "DEFAULT");
        }
    }
}
```

### 3.4 意图枚举

**位置**: `company-rag-rag/src/main/java/com/company/rag/rag/router/IntentType.java`

```java
public enum IntentType {
    /** 文档查询（RAG 流程） */
    DOCUMENT,
    
    /** 数据库查询（Agent 流程） */
    DATABASE,
    
    /** 代码查询（Agent 流程） */
    CODE,
    
    /** 闲聊（直接 LLM 回答） */
    CHAT
}
```

---

## 4. 接口设计

### 4.1 请求格式

**位置**: `company-rag-rag/src/main/java/com/company/rag/rag/response/ChatRequest.java`

```java
public class ChatRequest {
    /** 用户问题（必填） */
    private String query;
    
    /** 会话 ID（可选） */
    private String sessionId;
    
    /** 租户 ID（从 Header 获取，可选） */
    private Integer tenantId;
    
    /** RAG 检索条数（可选，默认 10） */
    private Integer topK;
    
    /** 是否启用 Rerank（可选，默认 true） */
    private Boolean enableRerank;
    
    /** 是否包含调试信息（可选，默认 false） */
    private Boolean includeDebug;
    
    /** 手动指定模式（可选，默认 auto） */
    private String mode;  // auto, rag, agent
    
    // getters/setters
}
```

**请求示例**:
```http
POST /api/chat
Content-Type: application/json
X-Tenant-Id: 1

{
  "query": "公司有多少员工？",
  "sessionId": "session_123",
  "topK": 10,
  "enableRerank": true,
  "includeDebug": false
}
```

### 4.2 响应格式

**位置**: `company-rag-rag/src/main/java/com/company/rag/rag/response/ChatResponse.java`

```java
public class ChatResponse {
    /** 回答内容 */
    private String answer;
    
    /** 来源文档/工具列表 */
    private List<String> sources;
    
    /** 性能指标 */
    private ChatMetrics metrics;
    
    /** 调试信息（仅 includeDebug=true 时返回） */
    private DebugInfo debug;
    
    // 静态方法创建降级回答
    public static ChatResponse fallback(String message) {
        ChatResponse response = new ChatResponse();
        response.setAnswer(message);
        response.setSources(Collections.emptyList());
        response.setMetrics(ChatMetrics.empty());
        return response;
    }
}
```

**响应示例（正常）**:
```json
{
  "code": 200,
  "data": {
    "answer": "公司目前有 500 名员工，分布在 5 个部门...",
    "sources": ["员工手册.pdf", "组织架构.docx"],
    "metrics": {
      "totalMs": 1200,
      "tokens": 350,
      "intent": "DATABASE",
      "routePath": "ChatRouter → RagAgentService"
    }
  }
}
```

**响应示例（含调试信息）**:
```json
{
  "code": 200,
  "data": {
    "answer": "公司目前有 500 名员工，分布在 5 个部门...",
    "sources": ["员工手册.pdf", "组织架构.docx"],
    "metrics": {
      "totalMs": 1200,
      "tokens": 350,
      "intent": "DATABASE",
      "routePath": "ChatRouter → RagAgentService"
    },
    "debug": {
      "intent": "DATABASE",
      "recognizeSource": "RULE",
      "confidence": 0.95,
      "toolUsed": "database_query",
      "routePath": "ChatRouter → RagAgentService → DatabaseQueryTool"
    }
  }
}
```

### 4.3 调试信息

**位置**: `company-rag-rag/src/main/java/com/company/rag/rag/response/DebugInfo.java`

```java
public class DebugInfo {
    /** 识别的意图 */
    private String intent;
    
    /** 识别来源（RULE/LLM/DEFAULT） */
    private String recognizeSource;
    
    /** 置信度（0-1） */
    private Double confidence;
    
    /** 使用的工具（Agent 模式） */
    private String toolUsed;
    
    /** 完整路由路径 */
    private String routePath;
    
    // getters/setters
}
```

---

## 5. 降级策略

### 5.1 功能分级

| 等级 | 功能 | 保障目标 | 降级措施 |
|------|------|----------|----------|
| **P0** | 核心回答 | 必须保证 | LLM 不可用 → 预设提示 |
| **P1** | 准确路由 | 尽量保证 | 识别失败 → 默认 DOCUMENT |
| **P1** | 来源信息 | 尽量保证 | 提取失败 → 空来源 |
| **P2** | 性能指标 | 可牺牲 | 收集失败 → 默认值 |
| **P2** | 会话保存 | 可牺牲 | 保存失败 → 记录日志 |
| **P2** | 调试信息 | 可牺牲 | 收集失败 → 忽略 |

### 5.2 降级流程图

```
用户请求
  ↓
┌─────────────────────────────────────┐
│ P0: 入口异常捕获                     │
│ - TimeoutException → 降级提示        │
│ - CriticalException → 降级提示       │
│ - Exception → 兜底回答               │
└─────────────────────────────────────┘
  ↓
┌─────────────────────────────────────┐
│ P1: 意图识别                         │
│ - 失败 → 默认 DOCUMENT 路由          │
└─────────────────────────────────────┘
  ↓
┌─────────────────────────────────────┐
│ P1: 路由到处理器                     │
│ - RAG → 检索失败 → 纯 LLM            │
│ - Agent → 工具失败 → 降级 RAG → LLM  │
│ - CHAT → 直接 LLM                   │
└─────────────────────────────────────┘
  ↓
┌─────────────────────────────────────┐
│ P1: 来源信息提取                     │
│ - 失败 → 空来源 (不影响回答)         │
└─────────────────────────────────────┘
  ↓
┌─────────────────────────────────────┐
│ P2: 指标收集                         │
│ - 失败 → 默认值 (不影响回答)         │
└─────────────────────────────────────┘
  ↓
┌─────────────────────────────────────┐
│ P2: 会话保存                         │
│ - 失败 → 记录日志 (不影响回答)       │
└─────────────────────────────────────┘
  ↓
返回回答给用户
```

### 5.3 降级代码示例

```java
// P0 级：核心处理降级
public ChatResponse route(ChatRequest request) {
    try {
        IntentType intent = recognizeIntentSafely(request.getQuery());
        return processByIntent(intent, request);
    } catch (TimeoutException e) {
        log.error("请求超时", e);
        return ChatResponse.fallback("抱歉，请求处理超时，请尝试简化问题后重试。");
    } catch (Exception e) {
        log.error("路由处理失败，使用兜底回答", e);
        return ChatResponse.fallback("抱歉，系统遇到一些问题，暂时无法回答您的问题。");
    }
}

// P1 级：意图识别降级
private IntentType recognizeIntentSafely(String query) {
    try {
        IntentResult result = intentRecognizer.recognize(query);
        return result.getIntent();
    } catch (Exception e) {
        log.warn("意图识别失败，使用默认路由 (DOCUMENT)", e);
        return IntentType.DOCUMENT;
    }
}

// P1 级：RAG 检索降级
public ChatResponse searchAndAnswer(ChatRequest request) {
    List<Document> docs = ragService.search(request.getQuery(), request.getTopK());
    if (docs.isEmpty()) {
        log.warn("RAG 检索无结果，降级到纯 LLM 回答");
        String answer = chatModel.call(new Prompt(request.getQuery()));
        return ChatResponse.builder()
            .answer(answer)
            .sources(Collections.emptyList())
            .build();
    }
    // 正常 RAG 流程...
}

// P2 级：会话保存降级
try {
    sessionService.saveMessage(sessionId, message);
} catch (Exception e) {
    log.warn("会话保存失败，不影响当前回答", e);
    // 不抛出异常，不影响用户得到回答
}
```

---

## 6. 数据流

```
1. 用户发送问题
   ↓
2. 前端调用 POST /api/chat
   ↓
3. ChatController 接收请求
   ↓
4. ChatRouter.route() 入口
   ↓
5. IntentRecognizer.recognize() 意图识别
   ├─ 规则匹配（高置信度）→ 返回意图
   └─ 规则失败 → LLM 识别 → 返回意图
   ↓
6. 根据意图路由到处理器
   ├─ DOCUMENT → RagService.searchAndAnswer()
   ├─ DATABASE/CODE → RagAgentService.process()
   └─ CHAT → 直接 LLM 回答
   ↓
7. 收集响应数据
   ├─ 回答内容（P0）
   ├─ 来源信息（P1）
   ├─ 性能指标（P2）
   └─ 调试信息（P2，可选）
   ↓
8. 保存会话（P2，失败不影响响应）
   ↓
9. 返回 ChatResponse 给前端
```

---

## 7. 文件结构

```
company-rag-rag/
└── src/main/java/com/company/rag/rag/
    ├── router/
    │   ├── ChatRouter.java           # 新增：路由核心
    │   ├── IntentRecognizer.java     # 新增：意图识别
    │   ├── IntentType.java           # 新增：意图枚举
    │   ├── IntentResult.java         # 新增：识别结果
    │   └── PatternRule.java          # 新增：规则定义
    └── response/
        ├── ChatResponse.java         # 新增：统一响应
        ├── ChatRequest.java          # 新增：统一请求
        ├── ChatMetrics.java          # 新增：指标
        └── DebugInfo.java            # 新增：调试信息

company-rag-web/
└── src/main/java/com/company/rag/web/controller/
    ├── ChatController.java           # 新增：统一入口
    ├── AgentController.java          # 保留：供直接调用
    └── RagController.java            # 保留：供直接调用

company-rag-web/
└── src/main/resources/templates/
    └── index.html                    # 修改：添加统一调用逻辑
```

---

## 8. 前端集成方案

### 8.1 第一阶段：最小改动 + 高级模式（本次实施）

**实现策略**:
- 统一调用 `/api/chat`
- 添加隐藏的模式切换（默认"智能自动"）
- 收集路由数据和用户反馈

**前端代码改动** (约 50-80 行):

```html
<!-- 在聊天界面头部添加高级模式切换（默认隐藏） -->
<div class="advanced-settings" v-if="showAdvanced">
  <select v-model="chatMode">
    <option value="auto">智能自动</option>
    <option value="rag">仅查文档</option>
    <option value="agent">Agent 模式</option>
  </select>
</div>

<script>
// 修改 sendMessage 函数
async function sendMessage() {
  const reqBody = {
    query: userInput,
    sessionId: currentSessionId,
    topK: 10,
    enableRerank: true,
    // 仅高级模式非 auto 时传递 mode 参数
    mode: chatMode === 'auto' ? undefined : chatMode
  };
  
  // 统一调用 /api/chat（替换原来的 /api/rag/search）
  const res = await fetch('/api/chat', {
    method: 'POST',
    headers: { 
      'Content-Type': 'application/json',
      'X-Tenant-Id': tenantId 
    },
    body: JSON.stringify(reqBody)
  });
  
  const json = await res.json();
  if (json.code === 200 && json.data) {
    messages.value.push({
      role: 'assistant',
      content: json.data.answer,
      sources: json.data.sources
    });
  }
}
</script>
```

### 8.2 第二阶段：完全统一（1-2 个月后）

**触发条件**:
- 路由准确率稳定在 90%+
- 系统运行 2-4 周无明显问题
- 用户反馈良好

**实现方式**:
- 移除模式切换 UI（或保留但默认隐藏更深）
- 前端代码完全统一调用 `/api/chat`
- 后端根据实际数据优化路由规则

---

## 9. 监控指标

### 9.1 核心指标

```yaml
# 请求量指标
chat.requests.total: counter        # 总请求数
chat.requests.by_intent: counter    # 按意图分类 (DOCUMENT/DATABASE/CODE/CHAT)
chat.requests.by_source: counter    # 按识别来源 (RULE/LLM/DEFAULT)

# 性能指标
chat.latency.ms: histogram          # 响应延迟分布
chat.latency.p99: gauge             # P99 延迟

# 质量指标
chat.router.fallback.count: counter # 降级次数
chat.intent.accuracy: gauge         # 识别准确率（需用户反馈）

# 业务指标
chat.tokens.total: counter          # Token 消耗总量
chat.sessions.active: gauge         # 活跃会话数
```

### 9.2 日志记录

```java
// 关键日志点
log.info("意图识别：query={} intent={} source={} confidence={}", 
    query, result.getIntent(), result.getSource(), result.getConfidence());

log.info("路由决策：intent={} handler={}", intent, handlerName);

log.warn("降级触发：level={} reason={} query={}", level, reason, query);

log.error("路由失败：query={} error={}", query, e.getMessage());
```

---

## 10. 成功标准

### 10.1 技术指标

- [ ] 路由准确率 ≥ 90%（基于用户反馈抽样）
- [ ] 系统可用性 ≥ 99.9%（P0 级故障 < 0.1%）
- [ ] 平均响应时间 < 3 秒
- [ ] 降级策略有效（故障时自动降级，不影响核心功能）
- [ ] 意图识别成本降低 ≥ 70%（相比纯 LLM 方案）

### 10.2 用户体验指标

- [ ] 前端集成简单（< 100 行代码改动）
- [ ] 用户无需理解 RAG/Agent 区别
- [ ] 高级模式可供调试使用
- [ ] 降级时用户收到友好提示

### 10.3 运维指标

- [ ] 完整的监控指标收集
- [ ] 关键日志记录
- [ ] 降级开关可配置
- [ ] 支持灰度发布

---

## 11. 风险与缓解

### 11.1 技术风险

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|----------|
| 意图识别准确率低 | 用户体验差 | 中 | 混合策略 + 人工反馈优化 |
| LLM 服务不稳定 | 系统不可用 | 低 | 熔断 + 降级到规则 |
| 响应延迟增加 | 用户体验差 | 中 | 异步处理 + 超时控制 |
| Token 成本超预算 | 成本增加 | 中 | 混合策略 + 限流 |

### 11.2 业务风险

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|----------|
| 用户不接受自动路由 | 使用率低 | 低 | 高级模式手动切换 |
| 回答质量下降 | 用户流失 | 中 | A/B 测试 + 逐步放量 |
| 前端兼容性问题 | 部分用户无法使用 | 低 | 渐进式发布 + 快速回滚 |

---

## 12. 实施计划

### Phase 1：后端开发（预计 3-5 天）

- [ ] 创建 `ChatRouter` 核心路由类
- [ ] 创建 `IntentRecognizer` 意图识别类
- [ ] 创建 `IntentType`、`IntentResult`、`PatternRule` 等辅助类
- [ ] 创建 `ChatRequest`、`ChatResponse`、`ChatMetrics`、`DebugInfo` 等响应类
- [ ] 创建 `ChatController` 统一入口
- [ ] 编写单元测试

### Phase 2：前端集成（预计 1-2 天）

- [ ] 修改 `index.html` 添加统一调用逻辑
- [ ] 添加高级模式切换 UI（默认隐藏）
- [ ] 测试 RAG/Agent/Chat 三种模式
- [ ] 测试降级场景

### Phase 3：测试与优化（预计 2-3 天）

- [ ] 功能测试（正常流程）
- [ ] 降级测试（各种故障场景）
- [ ] 性能测试（响应时间、并发）
- [ ] 意图识别准确率测试
- [ ] 根据测试结果优化规则和阈值

### Phase 4：上线与监控（预计 1-2 周）

- [ ] 灰度发布（10% 流量）
- [ ] 监控指标收集
- [ ] 用户反馈收集
- [ ] 根据数据优化路由规则
- [ ] 全量发布

---

## 13. 后续演进

### 短期（1-3 个月）

- [ ] 基于用户反馈优化意图识别规则
- [ ] 添加更多 Agent 工具（查询 ERP、CRM 等）
- [ ] 支持多轮对话上下文
- [ ] 添加回答质量评分功能

### 中期（3-6 个月）

- [ ] 引入机器学习优化意图识别
- [ ] 支持个性化路由（基于用户历史行为）
- [ ] 添加 A/B 测试框架
- [ ] 支持多租户差异化配置

### 长期（6-12 个月）

- [ ] 构建 Agent 工具市场
- [ ] 支持用户自定义工具
- [ ] 跨租户知识共享
- [ ] 多模态支持（图片、语音）

---

## 14. 附录

### 14.1 意图识别规则清单

**DATABASE 意图规则**:
- `.*(多少 | 几 个 | 数量 | 统计 | 汇总 | 平均 | 最大 | 最小).*`
- `.*(查询 | 查找 | 检索).*(数据 | 记录 | 用户 | 订单 | 员工 | 产品).*`
- `.*(列出 | 显示 | 给我).*(所有 | 全部).*(数据 | 记录).*`

**CODE 意图规则**:
- `.*(接口 | 类 | 方法 | 函数 | 代码 | 实现 | 源码).* (在哪 | 查找 | 搜索).*`
- `.*(怎么 | 如何 | 怎样).*(实现 | 写 | 完成).* (功能 | 模块).*`
- `.*(代码 | 文件).*(路径 | 位置 | 目录).*`

**CHAT 意图规则**:
- `.*(你好 | 您好 | 嗨 |hello|hi).*`
- `.*(谢谢 | 感谢 | 辛苦了).*`
- `.*(你 (是 | 叫 | 会 | 能).*)|(谁 | 什么).*`

**DOCUMENT 意图**: 默认路由，无特定规则

### 14.2 置信度阈值配置

```yaml
# 可配置在 application.yml
rag:
  router:
    intent:
      rule-threshold: 0.8      # 规则匹配置信度阈值
      llm-timeout: 5000        # LLM 识别超时 (ms)
      default-intent: DOCUMENT # 默认意图
```

### 14.3 降级开关配置

```yaml
# 可配置在 application.yml
rag:
  degraded: false              # 全局降级模式
  fallback:
    enabled: true              # 启用降级
    cache-enabled: true        # 使用缓存问答降级
    llm-direct: true           # 降级到纯 LLM
```

---

## 15. 审批记录

| 日期 | 审批人 | 意见 | 状态 |
|------|--------|------|------|
| - | - | - | 待审批 |

---

**文档结束**
