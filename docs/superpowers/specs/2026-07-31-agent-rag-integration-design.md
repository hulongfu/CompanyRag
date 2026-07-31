# CompanyRag 架构演进：分层式 RAG 工具化设计

**日期**: 2026-07-31  
**作者**: AI Assistant  
**状态**: 已批准  
**对标项目**: smart-dev-assistant

---

## 1. 背景与目标

### 1.1 背景

CompanyRag 当前定位为"企业知识库 RAG 系统"，实现了深度 RAG 能力（混合检索 + Rerank + 流式回答）。与此同时，项目新增了 Agent 功能，支持通过自然语言调用多个工具（数据库查询、代码搜索、API 文档生成）。

然而，当前架构存在以下问题：
- **两个独立入口**：`/api/agent/chat` 和 `/api/rag/search`，用户需要明确知道使用哪个接口
- **LLM 无法调用 RAG**：Agent 编排层无法自动决定何时使用 RAG 能力
- **与行业最佳实践不一致**：对标项目 smart-dev-assistant 将 RAG 作为工具之一，由 LLM 自动编排

### 1.2 目标

将 CompanyRag 从"RAG 系统"演进为"企业级智能助手平台"，采用分层架构：
- RAG 作为基础设施能力，可被 Agent 编排层调用
- 统一对话入口，LLM 自动决定调用哪个工具
- 保留深度 RAG 能力（混合检索 + Rerank）
- 向后兼容，保留独立 RAG 入口

### 1.3 设计原则

1. **分层清晰**：能力层（RAG 引擎）与编排层（Agent）职责分离
2. **工具化**：RAG 封装为 Spring AI 工具，供 LLM 调用
3. **向后兼容**：保留现有 `/api/rag/search` 接口
4. **最小改动**：不破坏现有模块边界，适度整合

---

## 2. 架构设计

### 2.1 架构模式：分层式（模式 C）

```
┌─────────────────────────────────────────────────┐
│          表现层 (company-rag-web)                │
├─────────────────────────────────────────────────┤
│  ChatController (统一入口)                       │
│  • POST /api/chat          (Agent 对话)          │
│  • POST /api/rag/search    (保留，@Deprecated)   │
└──────────────────┬──────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────┐
│          编排层 (company-rag-agent)              │
├─────────────────────────────────────────────────┤
│  RagAgentService                                │
│  └─ ChatClient (Spring AI Function Calling)     │
│      ├── databaseQueryTool                      │
│      ├── codeSearchTool                         │
│      ├── apiDocTool                             │
│      └── searchKnowledgeBaseTool (新增)         │
└─────────────────────────────────────────────────┘
                   │
┌──────────────────▼──────────────────────────────┐
│          能力层 (company-rag-rag)                │
├─────────────────────────────────────────────────┤
│  • RagSearchServiceImpl (混合检索 + Rerank)     │
│  • KnowledgeBaseTool (新增，封装 RAG 能力)      │
│      └─ @Tool(name="searchKnowledgeBase")       │
│      └─ 内部调用 RagSearchServiceImpl           │
└─────────────────────────────────────────────────┘
```

### 2.2 架构分层职责

| 层级 | 模块 | 职责 | 关键组件 |
|------|------|------|---------|
| **表现层** | company-rag-web | 统一入口，处理 HTTP 请求 | ChatController |
| **编排层** | company-rag-agent | Agent 编排，LLM 决定工具调用 | RagAgentService, ChatClient |
| **能力层** | company-rag-rag | RAG 引擎 + 工具封装 | RagSearchServiceImpl, KnowledgeBaseTool |

### 2.3 关键改进

1. **统一入口**：`/api/chat` 作为唯一 Agent 对话入口
2. **LLM 自动编排**：Spring AI Function Calling 机制，LLM 决定何时调用 RAG
3. **RAG 工具化**：`KnowledgeBaseTool` 封装现有 RAG 引擎，暴露为 `@Tool`
4. **向后兼容**：保留 `/api/rag/search` 接口，标记为 `@Deprecated`

---

## 3. 核心组件设计

### 3.1 KnowledgeBaseTool

**位置**: `company-rag-rag/src/main/java/com/company/rag/rag/tools/KnowledgeBaseTool.java`

**职责**: 将 RAG 引擎封装为 Spring AI 工具

**关键代码**:
```java
@Component
public class KnowledgeBaseTool {
    
    private final RagSearchService ragSearchService;
    private final ToolCallRecorder recorder;
    
    @Tool(
        name = "searchKnowledgeBase",
        description = "在企业知识库文档中检索信息，包括 Markdown、PDF、Word、TXT 文件。"
                    + "适用于查询 README、设计文档、使用手册、FAQ、流程规范、项目说明等。"
                    + "不搜索源代码文件（.java/.ts/.py 等）。"
    )
    public KnowledgeBaseResult searchKnowledgeBase(
            @ToolParam(description = "用户自然语言问题") String question,
            @ToolParam(description = "返回文档片段数量上限，默认 5", required = false) Integer topK) {
        
        // 1. 参数校验
        if (question == null || question.trim().isEmpty()) {
            return KnowledgeBaseResult.failed("问题不能为空");
        }
        
        // 2. 记录工具调用开始
        recorder.recordStart("searchKnowledgeBase", Map.of("question", question, "topK", topK));
        
        try {
            // 3. 调用 RAG 引擎（混合检索 + Rerank）
            RagQuery query = new RagQuery(question, topK != null ? topK : 5);
            RagResult result = ragSearchService.search(query);
            
            // 4. 组装结果（带引用来源）
            KnowledgeBaseResult response = convertToKnowledgeBaseResult(result);
            
            // 5. 记录工具调用结束
            recorder.recordEnd("searchKnowledgeBase", response.isSuccess() ? "success" : "failed");
            
            return response;
        } catch (Exception e) {
            log.error("知识库工具调用失败：question={}, err={}", question, e.getMessage());
            recorder.recordEnd("searchKnowledgeBase", "failed");
            return KnowledgeBaseResult.failed("工具调用失败：" + e.getMessage());
        }
    }
    
    private KnowledgeBaseResult convertToKnowledgeBaseResult(RagResult ragResult) {
        // 转换逻辑：RagResult → KnowledgeBaseResult
        // 包含答案、引用来源、相似度分数等
    }
}
```

**依赖**:
- `RagSearchService`（现有，`company-rag-rag` 模块）
- `ToolCallRecorder`（需迁移到 `company-rag-common`）

---

### 3.2 AgentToolConfig 调整

**位置**: `company-rag-agent/src/main/java/com/company/rag/agent/config/AgentToolConfig.java`

**改动**: 新增 `KnowledgeBaseTool` 注册

```java
@Configuration
public class AgentToolConfig {
    
    @Bean
    public ToolCallbackProvider toolCallbackProvider(
            DatabaseQueryTool databaseQueryTool,
            ApiDocTool apiDocTool,
            CodeSearchTool codeSearchTool,
            KnowledgeBaseTool knowledgeBaseTool) {  // ← 新增
        
        return MethodToolCallbackProvider.builder()
                .toolObjects(databaseQueryTool, apiDocTool, 
                           codeSearchTool, knowledgeBaseTool)
                .build();
    }
}
```

---

### 3.3 ChatController（统一入口）

**位置**: `company-rag-web/src/main/java/com/company/rag/web/controller/ChatController.java`

**改动**: 合并原有 `AgentController` 和 `RagController`

```java
@RestController
@RequestMapping("/api")
public class ChatController {
    
    private final RagAgentService ragAgentService;
    private final RagSearchService ragSearchService;
    
    /**
     * 统一对话入口（Agent 编排，LLM 决定调用工具）
     */
    @PostMapping("/chat")
    public R<ChatResponse> chat(@RequestBody ChatRequest request) {
        AgentResult result = ragAgentService.process(request.getMessage());
        return R.success(new ChatResponse(result.getContent()));
    }
    
    /**
     * 保留独立 RAG 入口（标记为 Deprecated，供现有前端使用）
     */
    @PostMapping("/rag/search")
    @Deprecated
    public R<RagResult> ragSearch(@RequestBody RagQuery query) {
        return R.success(ragSearchService.search(query));
    }
}
```

---

## 4. 数据流设计

### 4.1 场景 1：用户通过 Agent 对话调用 RAG

```
用户输入："怎么申请测试环境？"
    ↓
ChatController.chat()
    ↓
RagAgentService.process()
    ↓
ChatClient.prompt("怎么申请测试环境？")
    ↓
[LLM 分析] → 决定调用 searchKnowledgeBase 工具
    ↓
KnowledgeBaseTool.searchKnowledgeBase(question="怎么申请测试环境？")
    ↓
RagSearchServiceImpl.search()  ← 现有 RAG 引擎
    ├─→ VectorRetriever.retrieve()     (向量检索，topK=10)
    ├─→ FullTextRetriever.retrieve()   (全文检索)
    ├─→ FuzzyRetriever.retrieve()      (模糊检索)
    ├─→ CrossEncoderReranker.rerank()  (重排序，topK=5)
    └─→ 组装结果（带 chunk 内容、来源、分数）
    ↓
KnowledgeBaseTool 组装带引用的答案
    ↓
LLM 接收结果，生成自然语言回复
    ↓
ChatController 返回给用户
```

**关键点**: LLM 自动决定调用 RAG 工具，而不是硬编码路由

---

### 4.2 场景 2：用户直接使用独立 RAG 入口

```
前端提交 RAG 查询表单
    ↓
ChatController.ragSearch()  ← @Deprecated 但保留
    ↓
RagSearchServiceImpl.search()
    ├─→ 混合检索（向量 + 全文 + 模糊）
    ├─→ CrossEncoder Rerank
    └─→ 返回 RagResult
```

**关键点**: 保留向后兼容，现有前端无需立即改造

---

## 5. 模块依赖调整

### 5.1 改造前依赖关系
```
company-rag-agent
└── 无依赖 company-rag-rag

company-rag-rag
└── 独立模块
```

### 5.2 改造后依赖关系
```
company-rag-agent
└── 依赖 company-rag-rag  ← 新增（用于 KnowledgeBaseTool）

company-rag-rag
├── 新增 KnowledgeBaseTool
└── 依赖 company-rag-common.tool  ← ToolCallRecorder 迁移至此
```

### 5.3 ToolCallRecorder 迁移

**问题**: `ToolCallRecorder` 目前在 `company-rag-agent`，但 `KnowledgeBaseTool` 在 `company-rag-rag`，存在循环依赖风险

**解决方案**: 将 `ToolCallRecorder` 抽取到 `company-rag-common`

**迁移步骤**:
1. 在 `company-rag-common` 创建新包：`com.company.rag.common.tool`
2. 移动 `ToolCallRecorder` 类及相关模型
3. 更新 `company-rag-agent` 的 `pom.xml`，移除 `ToolCallRecorder` 相关代码
4. 更新 `company-rag-rag` 的 `pom.xml`，添加对 `company-rag-common` 的依赖
5. 更新所有引用 `ToolCallRecorder` 的 import 语句

---

## 6. 错误处理设计

### 6.1 RAG 工具调用失败场景

| 错误类型 | 处理方式 | 用户感知 |
|---------|---------|---------|
| 向量库连接失败 | 熔断降级，返回"知识库暂时不可用" | "抱歉，知识库服务暂时不可用" |
| 检索结果为空 | 返回空结果，LLM 告知用户 | "未找到相关信息" |
| Rerank 服务超时 | 使用原始检索结果（跳过 Rerank） | 正常返回，质量略降 |
| LLM 调用失败 | 返回检索结果（纯文档片段） | "找到以下文档片段..." |

### 6.2 降级策略

```java
// KnowledgeBaseTool 内部
try {
    // 调用 RAG 引擎
    RagResult result = ragSearchService.search(query);
    return KnowledgeBaseResult.ok(result);
} catch (CircuitBreakerOpenException e) {
    log.warn("RAG 服务熔断", e);
    return KnowledgeBaseResult.failed("知识库服务暂时不可用");
} catch (TimeoutException e) {
    log.warn("RAG 检索超时", e);
    return KnowledgeBaseResult.failed("检索超时，请稍后重试");
}
```

---

## 7. 测试策略

### 7.1 单元测试（新增）

| 测试类 | 测试内容 | 优先级 |
|-------|---------|-------|
| `KnowledgeBaseToolTest` | 工具参数校验、结果组装 | 高 |
| `KnowledgeBaseToolTest.integration` | 与 RAG 引擎集成 | 高 |
| `RagAgentServiceToolIntegrationTest` | Agent 编排层能否正确调用 RAG 工具 | 高 |

### 7.2 集成测试（现有测试保持不变）

- `RagSearchServiceTest` - RAG 引擎测试（保留）
- `CrossEncoderRerankerTest` - Rerank 测试（保留）
- `ApiDocToolTest` - 现有工具测试（保留）
- `DatabaseQueryToolTest` - 现有工具测试（保留）
- `CodeSearchToolTest` - 现有工具测试（保留）

### 7.3 端到端测试（新增）

| 测试场景 | 预期结果 |
|---------|---------|
| 用户输入"怎么申请测试环境？" | LLM 调用 `searchKnowledgeBase` 工具，返回带引用的答案 |
| 用户输入"查询最近 7 天注册的用户" | LLM 调用 `databaseQuery` 工具，返回表格数据 |
| 用户输入"生成 API 文档" | LLM 调用 `apiDoc` 工具，返回 API 列表 |

---

## 8. 迁移路径

### 阶段 1：新增 RAG 工具（1-2 天）
- [ ] 创建 `KnowledgeBaseTool.java`
- [ ] 创建 `KnowledgeBaseResult.java` 模型类（参考 smart-dev-assistant）
- [ ] 注册到 `ToolCallbackProvider`
- [ ] 编写单元测试

### 阶段 2：统一入口（1 天）
- [ ] 创建 `ChatController.java`（合并 `AgentController` + `RagController`）
- [ ] 保留 `/api/rag/search` 标记 `@Deprecated`
- [ ] 更新 `ChatRequest` 和 `ChatResponse` 模型
- [ ] 前端调用更新（可选，逐步迁移）

### 阶段 3：依赖调整（0.5 天）
- [ ] 将 `ToolCallRecorder` 迁移到 `company-rag-common`
- [ ] 更新模块依赖（`pom.xml`）
- [ ] 更新 import 语句

### 阶段 4：验证与优化（1 天）
- [ ] 集成测试验证
- [ ] 性能测试（RAG 工具化后延迟）
- [ ] 文档更新
- [ ] 代码审查

**总计**: 3.5-4.5 天

---

## 9. 架构对比总结

| 对比维度 | 改造前 | 改造后 |
|---------|-------|-------|
| **入口数量** | 2 个（`/api/agent/chat` + `/api/rag/search`） | 1 个统一入口 + 1 个兼容入口 |
| **LLM 编排** | 手动解析 `[USE_TOOL:xxx]` | Spring AI Function Calling（自动） |
| **RAG 可见性** | 独立功能，LLM 无法调用 | 工具化，LLM 可自动调用 |
| **模块耦合** | Agent 与 RAG 独立 | RAG 作为能力层，被 Agent 复用 |
| **向后兼容** | N/A | 保留 `/api/rag/search` |
| **对标 smart-dev-assistant** | ❌ 不一致 | ✅ 一致 |
| **代码复杂度** | 高（自定义编排逻辑） | 低（Spring AI 标准模式） |
| **可维护性** | 中（自定义逻辑难维护） | 高（框架标准模式） |

---

## 10. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|-----|------|---------|
| **循环依赖** | 编译失败 | 将 `ToolCallRecorder` 迁移到 `company-rag-common` |
| **LLM 误调用** | RAG 被错误场景触发 | 优化 `@Tool` description，明确适用场景 |
| **性能下降** | 工具调用增加延迟 | 保留独立 RAG 入口供高性能场景 |
| **前端不兼容** | 旧前端调用失败 | 保留 `/api/rag/search` 并标记 `@Deprecated` |
| **测试覆盖不足** | 生产环境 bug | 新增端到端测试，验证 LLM 工具调用逻辑 |

---

## 11. 验收标准

### 功能验收
- [ ] 用户输入"怎么申请测试环境？"，LLM 自动调用 RAG 工具并返回带引用的答案
- [ ] 用户输入"查询最近 7 天注册的用户"，LLM 自动调用数据库查询工具
- [ ] `/api/rag/search` 接口仍可正常使用
- [ ] 所有现有单元测试通过
- [ ] 新增端到端测试通过

### 非功能验收
- [ ] RAG 工具调用延迟 < 2 秒（包含 LLM 决策时间）
- [ ] 代码覆盖率不低于现有水平
- [ ] 无循环依赖
- [ ] 文档完整更新

---

## 12. 参考资料

- smart-dev-assistant 项目：`D:/tmp/smart-dev-assistant/src/main/java/com/example/smartdev/tools/KnowledgeBaseTool.java`
- smart-dev-assistant 项目：`D:/tmp/smart-dev-assistant/src/main/java/com/example/smartdev/service/KnowledgeBaseService.java`
- Spring AI 官方文档：https://docs.spring.io/spring-ai/reference/1.0/api/tools.html
- CompanyRag 现有 RAG 实现：`company-rag-rag/src/main/java/com/company/rag/rag/service/impl/RagSearchServiceImpl.java`

---

**文档版本**: 1.0  
**最后更新**: 2026-07-31  
**审批状态**: 已批准（用户确认）
