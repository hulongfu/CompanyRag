# 工具集路由召回层设计（Tool-Set Routing & Retrieval Layer）

> 日期：2026-09-19
> 类型：设计方案（Design Spec）
> 状态：待实现（本次仅设计，不实现）
> 目的：当 Agent 的工具与技能规模增长到上百个时，避免把全量"名称+描述"塞给大模型（省 Token、抗选错幻觉），实现"检索 → 召回 → 精准执行"的工具集调用闭环。
> 前置澄清：本方案是对现有 Agent 工具/技能体系的能力增强，不改动现有工具的对外语义。

## 1. 背景与问题

项目当前已有三层工具体系：
- **工具（Tool）**：`AgentTool` 接口 + `AgentToolRegistry` 注册中心（6 个）；`AggregatedToolCallbackProvider` 把它们转成 Spring AI `ToolCallback`，一次性全量暴露给模型。
- **技能（Skill）**：`FileSystemSkillRegistry` 扫描 SKILL.md；`SkillsAgentHook`（内含 `SkillsInterceptor`）把技能清单注入 system prompt，命中后用 `read_skill` 渐进式加载完整内容。
- **MCP**：可对接外部 MCP Server 的工具。

### 核心问题
经反编译 `spring-ai-alibaba-graph-core` / `spring-ai-alibaba-agent-framework` 1.1.2.0 确认的两条通道：

1. **工具**走 **function-calling 的 `tools` 参数**（`AgentLlmNode` 设置到 ChatClientRequest），**不进 system prompt**；但全量 `toolCallbackProviders` 会把所有工具一次性发送。
2. **技能**走 **system prompt**（`SkillsInterceptor.interceptModel` 调用 `SkillPromptConstants.buildSkillsPrompt(skillRegistry.listAll(), ...)`），**技能清单（名称+描述）是全量注入 system prompt 的**；渐进式的只是命中后加载的 SKILL.md 详细内容。

因此，无论工具还是技能，规模变大后都会出现"全量名称+描述进模型上下文"的问题。当前 6 工具 + 少量技能尚可接受，但上百个时会明显浪费 Token 并增加选错工具的幻觉。

## 2. 设计目标

- 工具/技能扩充到上百个时，每次 Agent 调用**只把与当期用户问题最相关的 top-N 个工具/技能的"名称+描述"**交给模型。
- 底层依旧是 Function Calling 闭环，但增加"先检索召回、再精准执行"的路由层。
- **现有工具的对外行为不变**（工具内部实现、参数、返回格式不因本方案改动）。

## 3. 技术选型：纯内存向量 + 线性余弦扫描

### 存储：不引入向量数据库，用进程内存
- **来源**：`AgentToolRegistry`（工具）+ 过滤型 `SkillRegistry`（技能），均为全局、非租户隔离。
- **构建**：启动时 `@PostConstruct` 自动扫描，逐项用现有 `EmbeddingModel`（通义千问 text-embedding-v3，1024 维）嵌入"名称+描述"，存内存 `List<float[]>` + 原文映射。
- **检索**：query 嵌入后与内存向量做线性余弦相似度，取 top-N。
- **刷新策略**：启动时构建、运行时不变，重启即重建（最简单）。
- **为什么不用 pgVector**：工具/技能是全局非租户隔离，现有 `PgVectorStore` 走租户 schema（`TenantAwareJdbcTemplate`），错位；且数据量小，"大炮打蚊子"。
- **为什么不用 Redis 向量**：现有 Redisson 是普通缓存用途，无向量模块；需运维改造，数据量小无必要。
- **为什么不用 Redis 内存缓存**：索引本就是进程内存、重启重建，无跨实例共享需求。

> 数据量论证：即使工具/技能增至几百个，每条仅"名称+描述"（几十到几百字符），总嵌入成本与内存占用依然很小；线性余弦扫描在几百维度 × 几百条规模的耗时毫秒级。

## 4. 整体架构与数据流

```
用户 query
   │
   ▼
ToolSkillRouter（召回器，纯内存向量）
   │  对同一 query 一次性召回
   ├──► 召回工具集(Set<String> toolNames, top-N)
   │       └──► 动态工具回调（RunnableConfig.DYNAMIC_TOOL_CALLBACKS_METADATA_KEY）
   │             └──► LLM 只看到这 N 个工具
   │
   └──► 召回技能集(Set<String> skillNames, top-N)
           └──► ThreadLocal（本次召回技能名）
                 └──► FilteredSkillRegistry.listAll() 只返回命中技能
                       └──► SkillsInterceptor 只把 N 个技能清单注入 system prompt
                             └──► LLM 只看到这 N 个技能
```

## 5. 组件设计

### 5.1 `ToolSkillRouter`（召回器）
- 持有内存向量索引（工具 + 技能两类实体，用 type 字段区分）。
- 方法：`ToolSkillRecall recall(String query, int topK)`，返回召回的工具名集合 + 技能名集合。
- 同一 query 一次嵌入、一次线性扫描，同时产出工具与技能两类召回结果。

### 5.2 工具接入：动态工具回调
- 复用框架原生 `RunnableConfig.DYNAMIC_TOOL_CALLBACKS_METADATA_KEY`。
- `RagAgentService.callAgentWithTimeout` 内，用召回的工具名从 `AgentToolRegistry` 取对应 `AgentTool`，转成 `ToolCallback[]`，通过 `call(messages, config)` 的 metadata 传入。
- 效果：不触碰全量 `AggregatedToolCallbackProvider`，LLM 只收到本次召回的 N 个工具。
- ⚠️ 实现时需核对 1.1.2.0 中该 key 的确切用法（`RunnableConfig` 已在字节码中确认存在 `DYNAMIC_TOOL_CALLBACKS_METADATA_KEY` 常量）。

### 5.3 技能接入：过滤型 `SkillRegistry` 代理（策略 A）
- 定义 `FilteredSkillRegistry implements SkillRegistry`，包装真实 `FileSystemSkillRegistry`。
- 核心：`listAll()` 先读"本次召回技能名"ThreadLocal；若非空则只返回命中技能，为空则回退全量（保底）。
- 其余 `SkillRegistry` 方法（`get/contains/size/reload/readSkillContent/getSkillLoadInstructions/getRegistryType/getSystemPromptTemplate`）透传或保持一致。
- 复用 `SkillsAgentHook` + `SkillsInterceptor` 原链路，仅把注入 hook 的 registry 替换为该过滤代理。
- ⚠️ `SkillRegistry` 接口方法（已从字节码确认）：`get(String)`、`listAll()`、`contains(String)`、`size()`、`reload()`、`readSkillContent(String)`、`getSkillLoadInstructions()`、`getRegistryType()`、`getSystemPromptTemplate()`。

### 5.4 状态注入：召回技能集 ThreadLocal（线程安全）
- 持有一个 `Set<String>` 的 ThreadLocal，记录"本次调用应暴露的技能名"。
- **注入点**：在 `RagAgentService.callAgentWithTimeout` 的线程池 async 块内、`streamingAgentExecutor.execute(messages)` 之前 set；`finally` 里 `clear()`。
- **线程安全论证**（关键）：Agent 执行位于有界线程池的池线程（`executorService`）。ThreadLocal 若只在请求线程设置，池线程读不到，会产生串号。解决方式与现有 `TenantContext` 完全同模式（RagAgentService.java:193-207 手动重设 → 216 finally clear）：
  1. 每个池线程任务启动时，在 async 块内先 set"本次召回技能集"；
  2. 执行完 `finally` 里 `clear()`，避免线程复用残留。
  - 并发多用户各自 set 各自 clear，互不干扰，无串号。

### 5.5 调用链路改动点
- `RagAgentService.callAgentWithTimeout`：async 块内、`execute()` 前调用 `ToolSkillRouter.recall(query)`，把结果分别用于动态工具回调 + 技能 ThreadLocal。
- `AgentConfig`：`SkillsAgentHook` 注入的 `SkillRegistry` 换为 `FilteredSkillRegistry`。
- 需要把 user query 传入（当前 `execute(messages)` 只传 messages；可从中取最新 user 消息，或方法签名扩展）。

## 6. 错误处理与降级

- 召回器异常：捕获后告警日志，**回退为当前现状**（工具全量 / 技能全集），保证 Agent 可用性不受影响。
- ThreadLocal 读取异常/为空：`FilteredSkillRegistry.listAll()` 回退全量。
- 动态工具回调注入失败：回退到全量 `toolCallbackProviders`（保底语义与现状一致）。
- 召回 top-N 为空：则回退全量，避免模型无可用工具。

## 7. 安全与多租户

- 工具/技能为全局资源，不按租户隔离；ThreadLocal 仅承载"本次暴露集合"，不含鉴权敏感数据。
- 不引入新的可伪造 ID 输入；不改变现有 `TenantContext` 鉴权逻辑。
- 召回仅基于"名称+描述"文本与向量相似度，不涉及权限判定。

## 8. 可观测性

- `ToolSkillRouter` 增加结构化日志：query、召回工具数、召回技能数、耗时。
- 通过现有 `ToolCallRecorder` / MDC traceId 关联每次召回与后续工具调用，便于排障。

## 9. 测试建议

- 单元测试：`FilteredSkillRegistry.listAll()` 在 ThreadLocal 有/无召回集时分别返回过滤/全量；`ToolSkillRouter` 余弦 top-N 排序正确。
- 集成测试：召回 query → 动态工具回调只含 N 工具；技能 prompt 只含 N 技能。
- 并发测试：多线程并发调用时技能 ThreadLocal 不串号（对应 §5.4）。
- 回归：当前 6 工具场景下召回 N=6 时行为与现状一致。

## 10. 决策记录（ADR 摘要）

| # | 决策 | 理由 |
|---|------|------|
| D1 | 存储用纯内存向量 + 线性余弦，不引入向量库 | 全局非租户、数据量小、零基础设施；pgVector/Redis 均不合适 |
| D2 | 工具走 `DYNAMIC_TOOL_CALLBACKS_METADATA_KEY` 动态回调 | 框架原生支持按次过滤，不触碰全量 provider |
| D3 | 技能用 `FilteredSkillRegistry` 过滤代理复用 `SkillsAgentHook` | 默认 hook 无过滤 API（已反编译确认），代理改动最小、保留框架行为 |
| D4 | 技能召回集用 ThreadLocal，在池线程 async 块内 set + finally clear | 与现有 `TenantContext` 同模式，天然防线程池复用串号 |
| D5 | 本次仅设计，不实现 | 当前 6 工具规模 YAGNI；待工具膨胀到几十个再落地 |

## 11. 范围与排期

- **状态：仅设计不实现**。本次产出本文档。
- 落地时机：当工具/技能规模增长到足以产生"全量 Token + 选错幻觉"问题时再启动实现。
- 实现时按 writing-plans 产出实施计划，验证遵循最小范围（仅 agent 模块相关类 + 新增召回器 + 相应单测/集成测试）。