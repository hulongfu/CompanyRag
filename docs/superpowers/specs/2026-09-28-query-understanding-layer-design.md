# 方案B：检索前 Query 理解层（Query Understanding Layer）设计 v1.0

> 日期：2026-09-28
> 类型：设计方案（Design Spec）
> 状态：待审阅（本次仅设计，不实现；HARD-GATE 未解除）
> 范围：在 RAG 检索链路「入口 → 多路召回」之间插入一个可关闭的 Query 理解层，把"原样进检索的用户问题"升级为"规范化 + 关键词化 + （后续）改写/消解后的检索输入"，并为其提供统一承载位与可观测产物。
> 前置澄清：本层不改变现有检索器/融合/重排/落库的对外语义；`enabled=false`（默认）时全链路行为与现状逐字节等价。

---

## 1. 目标

1. 给"用户原始问题"与"多路召回"之间补一个**可关闭、可观测、租户安全**的理解层，修复当前"query 原样进检索"导致的召回损失。
2. 建立**理解产物承载位**（当前 `RagQuery` 无任何承载位），使改写/关键词/意图等产物有唯一落点、可透传、可落库、可评测。
3. 优先用**零新依赖、零外部调用**的规则能力拿到最大召回收益（中文全文检索 0 命中问题）；LLM 改写/指代消解列为后续里程碑。
4. 与既有 `2026-09-19-toolset-routing-retrieval`（工具/技能召回）、`2026-09-13-hybrid-retrieval-workflow`（多路召回编排）职责正交，不重叠。

## 2. 现状回顾（真实机制，均经代码核实）

| # | 现状事实（证据） | 影响 |
|---|---|---|
| C1 | 主链路 `/api/chat` → `ChatController.chat:136/140` → `RagAgentService`（ReAct）→ LLM 自主决定把字符串传给 `KnowledgeBaseTool.searchKnowledgeBase`，`KnowledgeBaseTool:82` `query.setQuery(question)` **原样**进 `RagSearchService.search`。 | query 文本全程零加工；唯一"改写"是 LLM 自由填参，不可观测、不落库 |
| C2 | `RagSearchServiceImpl` 三个公开入口 `search:51`/`streamAnswer:146`/`retrieve:195` 内，`hybridRetrieve:208` 直接按策略把 `query.getQuery()` 原样传给三路检索器；唯一的"规范化"是 `buildCacheKey:226` 的 `query.getQuery().trim().toLowerCase()`（仅服务缓存 key，检索/rerank/prompt 仍用原文）。 | 口语化/错别字/噪声词/超长 query 直接进检索与 LLM |
| C3 | 多路召回节点 `VectorRetrieveNode:47`、`FullTextRetrieveNode`、`FuzzyRetrieveNode` 各自 `query.getQuery()` 原样取出，且 `TOP_K` 硬编码（向量/全文 50、模糊 30）。 | 无法按 query 特征自适应；理解产物无消费点 |
| C4 | `FullTextRetriever.buildTsQuery:59`：正则 `[a-zA-Z0-9]+` 抽 ASCII 词做前缀匹配，**英文词之间的中文段整块当作一个 term**，多词用 `&`（AND）连接；底层 `to_tsquery('pg_catalog.simple', …)` 对中文不分词。 | 中文长句全文路极易 0 命中，退化到只剩向量+模糊 |
| C5 | `RagQuery`（49 行）字段中 `documentIds`/`vectorWeight`/`keywordWeight`/`stream` 为死字段，**无任何理解产物承载位**（无 rewrittenQuery/keywords/intent/terms）。 | 理解层无处写入 |
| C6 | 入口对 `query` 无合法性/长度校验；`RagQuery.query` 为 null 时 `buildCacheKey:226` 直接 NPE。 | 空/超长/纯符号 query 直接进入 LLM |
| C7 | 意图识别 `router` 包（`IntentRecognizer`/`ChatRouter`）整体 `@Deprecated`，`ChatRouter` 在 `src/main` 零引用 → 运行时意图能力实际为零；意图体系仅 4 类、规则硬编码在构造函数、LLM 分支无熔断。 | 无意图细分、无落库；旧实现不可直接复用 |
| C8 | `rag_session` 无 intent/改写列；`RagSessionMeta` 有 `tags`/`metadata`(jsonb) 可作未来落点。 | 理解产物暂时无处持久化 |

> 结论：当前"理解"事实上外包给了 ReAct LLM 的自由填参，检索链路本身零理解能力，且中文全文检索存在结构性短板。这是 B 要解决的真问题。

## 3. 架构设计

### 3.1 插入点与总体结构（原则：单点插入、默认旁路）

在 `RagSearchServiceImpl` 三个公开入口（`search`/`streamAnswer`/`retrieve`）调用 `hybridRetrieve` **之前**，统一插入一次理解层调用：

```
入口(query)
  → queryUnderstandingService.enrich(query)   // 理解产物写回 RagQuery 新增字段；关闭时直接 return
  → hybridRetrieve(query)                      // 三路检索器改为优先消费"理解产物"，缺失时回退原文
  → rerank / finalFilter / prompt（不变）
```

- **单点插入**：理解层只在 `RagSearchServiceImpl` 调用一次，三路检索器/融合/重排/落库/Prompt 全部不感知"理解层存在"，只消费 `RagQuery` 字段。避免在 workflow 多个节点重复触发。
- **默认旁路**：`rag.query.enabled=false`（默认）时 `enrich` 第一行 `return`，`RagQuery` 新增字段保持 `null`，下游回退原文 → 行为与现状逐字节等价（见 §3.6 兼容性铁律）。
- **覆盖范围**：因所有 RAG 检索入口（`/api/chat` 经 `KnowledgeBaseTool`、`/api/rag/search`、`/api/rag/stream`）最终都汇聚到 `RagSearchServiceImpl`，单点插入即覆盖全部 RAG 链路，无需改各 Controller/Tool。

### 3.2 理解产物承载位（扩展 `RagQuery`，向后兼容）

在 `RagQuery` 新增字段（均默认 `null`，不改既有字段，避免破坏 `buildCacheKey` 与序列化）：

| 新字段 | 类型 | 含义 | 消费方 |
|---|---|---|---|
| `rewrittenQuery` | `String` | 规范化/改写后的检索文本；`null` 表示未改写 | 向量路、模糊路、rerank、prompt（`null` 时回退 `query`） |
| `searchTerms` | `List<String>` | 抽取出的检索关键词（含中文切分结果） | 全文路 `FullTextRetriever`（非空时走 OR 兜底，见 §3.4） |
| `intentHint` | `String` | 意图提示（M3 才写入，M1/M2 恒 null） | 预留，本期不消费 |

> 说明：`rewrittenQuery` 与 `searchTerms` 是 M1 唯二真正生效的产物。新增字段不进 `buildCacheKey` 的"参数指纹"讨论见 §7 风险 R1（缓存 key 必须改用 `rewrittenQuery`，否则改写后仍命中旧缓存）。

### 3.3 理解服务契约（接口 + 策略实现）

新增包 `com.company.rag.rag.query`（属 rag 模块，纯检索前处理，不碰 tenant/web）：

```java
public interface QueryUnderstandingService {
    /** 幂等：对同一 query 重复调用结果一致；关闭时不修改入参直接返回。 */
    void enrich(RagQuery query);
}
```

- 实现类 `QueryUnderstandingServiceImpl`，类级 `@ConditionalOnProperty(prefix="rag.query", name="enabled", havingValue="true")`（无 `matchIfMissing` → 缺省即不装配，与项目既有 `@ConditionalOnProperty` 门控风格一致）。
- `RagSearchServiceImpl` 用 `@Autowired(required=false) QueryUnderstandingService`（可选注入）：未装配（`enabled=false`）时为 `null`，入口判空跳过 —— 与 `ChatController:53` 对 `AnswerEvaluationService` 的可选注入范式一致，保证"关闭即零 Bean、零开销、零行为差异"。

### 3.4 M1 能力（本期实现，纯规则、零外部调用）

M1 只做两件零依赖、零 LLM、零新库的事，直击 C4/C6：

**能力①：输入规范化（治 C6）**
- `query` 为 `null`/空白 → 归一化为空并置 `rewrittenQuery=""`；入口据此**短路返回空结果**（不再 NPE、不再打 LLM）。
- 超长截断：`rewrittenQuery` 超过 `rag.query.max-length`（默认 512）截断，避免超长 query 打爆 embedding/token。
- 首尾空白、连续空白归一。

**能力②：中文关键词抽取（治 C4，最大召回收益）**
- 对 `rewrittenQuery` 抽取 `searchTerms`：ASCII 词（沿用现有 `[a-zA-Z0-9]+`）+ 中文按 **2-gram 滑动切分**（零依赖，无需引入 jieba/HanLP）。
- `FullTextRetriever` 增加**重载** `retrieve(String query, List<String> terms, int topK)`：
  - `terms` 非空 → 用 terms 以 **`|`（OR）** 连接构造 tsquery（解决中文整块 AND 必 0 命中）；
  - `terms` 为空 → 回退原 `buildTsQuery(query)`（**旧行为完全保留**，向后兼容）。
- 精度保护：OR 扩大召回后，仍由既有 `CrossEncoderReranker` + `ResultFilter` 收敛，不额外改排序。
- 向量路/模糊路/rerank/prompt 改用 `rewrittenQuery`（`null` 回退 `query`）；三路检索器读取 `RagQuery` 中已规范化的文本。

> 为什么 2-gram 而非引分词库：零新依赖、零运维、对"中文整块精确匹配必 0 命中"这一结构性缺陷是最小有效修复；2-gram 覆盖绝大多数中文词召回，精度损失由 rerank 兜底。引入 jieba 等列为 §8 后续。

### 3.5 后续里程碑（本期不做，见 §8）

- **M2 LLM 查询改写 + 指代消解**：结合 `RagChatMemory` 历史，用 LLM 把"它的第二步是什么"补全为自包含 query；**必须** `@CircuitBreaker(name="query-rewrite")` + `@RateLimiter`（遵守铁律：外部 LLM 调用必须熔断），降级回退 `rewrittenQuery=原文`。
- **M3 意图细分与路由**：重写（非复用 `@Deprecated` 的 `router` 包）意图层，产出结构化 `intentHint` 并落 `RagSessionMeta.metadata`。旧 `router` 包处理（删除或改造）单列评估，不在本期。

### 3.6 兼容性铁律（enabled=false 必须逐字节等价现状）

1. `enabled=false`（默认）：`QueryUnderstandingService` 不装配 → `RagSearchServiceImpl` 注入为 `null` → 入口判空跳过 → `RagQuery` 新字段全 `null`。
2. 下游回退路径：`rewrittenQuery==null` 用 `query`；`searchTerms` 空 → `FullTextRetriever` 走原 `buildTsQuery`。二者叠加使关闭态检索输入与现状完全相同。
3. `FullTextRetriever` 旧方法 `retrieve(String,int)` 保留不动，新方法为重载，不破坏既有调用与测试。

## 4. 数据流

```
用户 query
  │
  ▼
RagSearchServiceImpl.search / retrieve / streamAnswer
  │
  ├─(A) queryUnderstandingService != null ? enrich(query) : 跳过
  │       ├─ 规范化：null/空白→短路空结果；超长截断；空白归一   [C6]
  │       └─ 关键词：ASCII 词 + 中文 2-gram → query.searchTerms  [C4]
  │       （写回 query.rewrittenQuery / query.searchTerms）
  │
  ├─(B) hybridRetrieve(query)
  │       ├─ 向量路：VectorRetriever.retrieve(rewrittenQuery?:query, 50)
  │       ├─ 全文路：FullTextRetriever.retrieve(query, searchTerms, 50)  // terms 非空走 OR
  │       └─ 模糊路：FuzzyRetriever.retrieve(rewrittenQuery?:query, 30)
  │
  ├─(C) rerank(rewrittenQuery?:query, …) → finalFilter → prompt（均不变）
  │
  └─(D) buildCacheKey 改用 rewrittenQuery?:query（见 §7 R1）
```

执行顺序钉死：**先 enrich（判空可短路）→ 再检索**。短路发生在任何外部调用之前。

## 5. 测试策略（最窄范围）

- `QueryUnderstandingServiceImplTest`（纯单测，无 Spring）：
  - 正常：中英混合 query → `searchTerms` 含 ASCII 词与中文 2-gram；`rewrittenQuery` 已规范化。
  - 边界：长度恰好 `max-length` / 超 `max-length` 截断；单字中文（2-gram 退化）；纯 ASCII；纯符号。
  - 异常：`query=null` → `rewrittenQuery=""` 且标记短路；全空白 → 短路。
- `FullTextRetrieverTest`（mock `JdbcTemplate`）：`terms` 非空 → SQL 含 `|`（OR）；`terms` 空 → 回退 `&`（AND）旧行为；验证不改变 `LIMIT`。
- `RagSearchServiceImplTest`：`queryUnderstandingService=null`（关闭态）→ 检索调用参数与现状一致（回归保护）；`buildCacheKey` 用 `rewrittenQuery` 断言（改写后不复用旧缓存）。
- 运行（scoped）：`mvn -pl company-rag-rag -am test -Dtest=QueryUnderstandingServiceImplTest,FullTextRetrieverTest,RagSearchServiceImplTest`。

## 6. 改动清单

| 文件 | 改动 | 模块 |
|---|---|---|
| `rag/model/RagQuery.java` | 新增 `rewrittenQuery`/`searchTerms`/`intentHint` 三字段（默认 null） | rag |
| `rag/query/QueryUnderstandingService.java` | 新增接口 | rag |
| `rag/query/QueryUnderstandingServiceImpl.java` | 新增实现，类级 `@ConditionalOnProperty(rag.query.enabled=true)` | rag |
| `rag/retriever/impl/FullTextRetriever.java` | 新增重载 `retrieve(String,List<String>,int)`（terms 非空走 OR），旧方法不动 | rag |
| `rag/service/impl/RagSearchServiceImpl.java` | 三入口前插入 `enrich`（可选注入判空 + 短路）；三路检索/rerank/prompt 改读 `rewrittenQuery?:query`；`buildCacheKey` 改 `rewrittenQuery?:query` | rag |
| `application.yml` | 新增 `rag.query` 基段：`enabled: false`、`max-length: 512`、`term-extract-enabled: true` | bootstrap |

> 不改 Controller、不改 `KnowledgeBaseTool`、不改 workflow 节点、不改融合/重排/落库/Prompt 逻辑。`intentHint` 仅占位不消费。

## 7. 风险与观察项

- **R1（关键）缓存穿透改写**：`buildCacheKey:226` 现用 `query.getQuery()` 归一化。若引入 `rewrittenQuery` 而缓存 key 仍用原文，会出现"改写后检索、旧原文缓存命中"的错配。**必须**让 `buildCacheKey` 改用 `rewrittenQuery?:query`；且改写策略变更需随缓存版本失效（复用 `RagCacheManager` 版本号机制）。
- **R2 召回精度**：中文 2-gram + OR 会扩大全文召回，可能引入噪声 chunk。缓解：仅影响全文一路，向量/模糊不变，最终由 rerank + `maxPerDoc` 收敛；`term-extract-enabled=false` 可单独关闭能力②保留能力①。
- **R3 短路语义变更**：M1 让 null/空白 query 返回空结果而非抛 NPE。这是**有意的行为改进**，但改变了现状（现状是 500）。需在实现计划确认前端对空结果的处理；对 `/api/chat` 主链路无影响（其经 ReAct，空 query 通常 LLM 直接澄清）。
- **R4 铁律合规**：M1 零外部 LLM 调用，不触发"外部调用必须熔断"铁律；M2 引入 LLM 改写时**必须**先加 `@CircuitBreaker`/`@RateLimiter` 再合入。
- **R5 依赖方向**：理解层落在 rag 模块，仅依赖 rag 内部 + common，不新增跨模块依赖，符合边界。
- **R6 向后兼容**：`FullTextRetriever` 用重载而非改签名，`RagQuery` 用新增字段而非改语义，确保既有调用/测试/序列化不受影响。

## 8. 后续演进（本期不做）

- **M2**：LLM 查询改写 + 基于 `RagChatMemory` 历史的指代消解/follow-up 补全（带熔断）。
- **M3**：意图细分与自适应路由；结构化 `intentHint` 落 `RagSessionMeta.metadata`；评估是否删除 `@Deprecated` 的 `router` 包。
- 引入专业中文分词（jieba/HanLP）替换 2-gram，或引入 PG `zhparser`/`pg_jieba` 扩展改善 `content_tsv` 侧（需运维改造）。
- Query 理解效果的离线评测基线（当前 `RetrievalEvalRunner` 不覆盖改写维度）。

## 附录：配置键清单

| 键 | 默认值 | 语义 |
|---|---|---|
| `rag.query.enabled` | `false` | 理解层装配门控；`false`=不装配 Bean、全链路等价现状 |
| `rag.query.max-length` | `512` | `rewrittenQuery` 截断上限（字符） |
| `rag.query.term-extract-enabled` | `true` | 能力②（关键词抽取）开关；仅在 `enabled=true` 下生效，关闭则全文路回退旧 `buildTsQuery` |
