# 混合检索 StateGraph 工作流设计

> 日期：2026-09-13
> 类型：设计规格（Spec）
> 状态：待用户审阅
> 前置决策：继续采用 Spring AI Alibaba Graph（当前 `ReactAgent` 已基于 `spring-ai-alibaba-graph-core`），不引入 LangGraph4j。

## 1. 目标

在不改变对外行为的前提下，把当前 `MultiRetrieveServiceImpl` 内部的确定性混合检索流水线，重构成一张 Spring AI Alibaba `StateGraph` 工作流，以此体验图编排的工作流效果。要求**改动最小**、**改动范围封闭**。

**约束：**
- 不要求持久化 / 断点恢复（单次请求内运行完）。
- 保持 `MultiRetrieveService` 接口与 `RagSearchServiceImpl` 上层调用不变。
- 保留现有「单路检索失败不影响整体」的容错语义。
- 不触碰数据层与索引（向量维度 1024 / COSINE / HNSW 的 iron-rule 既有约束天然满足）。

## 2. 现状回顾

`MultiRetrieveServiceImpl.retrieve(RagQuery)` 当前是一条硬编码顺序流水线：

```
向量检索 → 全文检索 → 模糊检索（三路各自 try/catch）
  → 归一化（RankNormalizer）→ 融合（ResultFuser）→ 筛选（ResultFilter）
```

上层 `RagSearchServiceImpl.hybridRetrieve()` 通过 `multiRetrieveService.retrieve(query)` 调用，并解析 `RetrievalStrategy`（VECTOR_ONLY / FULLTEXT_ONLY / HYBRID）。

## 3. 架构设计

**核心思路：** 仅把 `MultiRetrieveServiceImpl.retrieve()` 委派给一张图工作流；业务逻辑复用现有组件，不重写。

### 3.1 新增组件（`workflow` 子包）

| 组件 | 职责 | 依赖 |
|---|---|---|
| `HybridRetrievalWorkflow` | 组装并执行图工作流；`execute(RagQuery)` 返回融合结果 | 各 Node、State |
| `HybridRetrievalState` | 图状态载体（query + 三路中间结果 + 最终筛选结果），实现 `AgentState` | —（纯数据） |
| `VectorRetrieveNode` | 向量检索写入 state | `VectorRetriever` |
| `FullTextRetrieveNode` | 全文检索写入 state | `FullTextRetriever` |
| `FuzzyRetrieveNode` | 模糊检索写入 state | `FuzzyRetriever` |
| `NormalizeFuseNode` | 归一化 + 融合（合并为一步，减少节点数） | `RankNormalizer`、`ResultFuser` |
| `FilterNode` | 最终筛选，结果写入 state 作为工作流输出 | `ResultFilter` |

### 3.2 图结构（HYBRID 请求）

```
START
  │
  ├──▶ vectorRetrieval ──┐
  ├──▶ fullTextRetrieval ┼──▶ normalizeAndFuse ──▶ finalFilter ──▶ END
  └──▶ fuzzyRetrieval ───┘
```

- 三个检索节点**无相互依赖**，Alibaba Graph 自动并行执行。
- 之后归一化融合、最终筛选依次串行。

### 3.3 策略分派保持

`RagSearchServiceImpl.hybridRetrieve()` 的策略分派（VECTOR_ONLY / FULLTEXT_ONLY / HYBRID）**不改动**。图工作流只承载默认 HYBRID 混合检索路径，即替换原 `multiRetrieveService.retrieve()` 内部实现。

## 4. 数据流

1. `RagSearchServiceImpl.hybridRetrieve()` 调用 `multiRetrieveService.retrieve(query)`（不变）。
2. `MultiRetrieveServiceImpl` 构造注入 `HybridRetrievalWorkflow`（单例懒加载，图编译一次复用）。
3. `retrieve()` 委派给 `workflow.execute(query)`：
   - 组装初始 state（写入 query 与检索参数）。
   - `compile().stream(initialState)` 遍历执行节点。
4. 三路检索节点并行写入各自 chunk → `normalizeAndFuse` 归一化融合 → `finalFilter` 筛选。
5. `workflow.execute` 从最终 state 读取筛选结果，返回 `List<RagResult.ChunkResult>`。

**API 事实（已核实 `spring-ai-alibaba-graph-core` 1.1.2.0）：**
- `StateGraph.addNode(String, ...)`、`addEdge`、`addConditionalEdges`。
- `CompiledGraph.invoke(Map, RunnableConfig)` 返回 `Optional<OverAllState>`；`stream` 返回 Flux。
- 通过 `DEFAULT_JACKSON_SERIALIZER` 或默认构造使用。

## 5. 错误处理（保留现有语义）

| 场景 | 图实现策略 |
|---|---|
| 单路检索抛异常 | Node 内部 try/catch，写空列表，**不上抛** → 融合仍产出其他路结果 |
| `normalizeAndFuse` / `finalFilter` 异常 | 节点抛错冒泡到 `execute()`，`MultiRetrieveServiceImpl.retrieve()` 外层 catch 返回空列表（与现状一致） |
| 租户上下文传播 | 检索节点依赖 `TenantContext`；沿用现有调用链在初始化 state 前保持上下文就绪，不新增切面 |

## 6. 测试策略

- **单元测试**：逐一测试各 Node（mock retriever/fuser/filter），验证 state 写入与容错分支。
- **工作流测试**：`HybridRetrievalWorkflow` 集成测试——单路失败仍返回其他路结果；全正常时结果与旧逻辑一致。
- **回归保障**：现有 `MultiRetrieveIntegrationTest` 应保持通过，作为重构不破坏功能的验证。
- 验证命令采用最窄范围：单模块相关测试类，不跑全仓库套件。

## 7. 改动清单

- **修改**：`company-rag-rag/.../service/impl/MultiRetrieveServiceImpl.java`（内部委派给工作流）。
- **新增**：`company-rag-rag/.../workflow/` 下 7 个类（Workflow / State / 5 个 Node，其中检索 3 个、融合类 1、筛选 1）。
- **不动**：`MultiRetrieveService` 接口、`RagSearchServiceImpl`、Controller、DTO、数据层。

## 8. 风险与观察项

- **行为等价风险**：图节点并行与旧的顺序执行在结果上应一致（三路互相独立，融合顺序不敏感）；通过现有集成测试回归确认。
- **图编排学习成本**：为本项目引入 StateGraph 编排风格，需在文档与后续 plan 中给出最小可用示例。
- **未来重估 LangGraph4j**：若演进到长任务断点续跑 / 人工介入审核 / 跨请求状态恢复，再评估；当前不引入。
