# 方案B：检索前 Query 理解层（Query Understanding Layer）设计 v1.1

> 日期：2026-09-28
> 类型：设计方案（Design Spec）
> 状态：已冻结（两份用户裁决已按助手推荐定案：①模糊路 C9 本期不修、只记为观察项 R7；②**缓做 B**，理由见 R10 收益边界 —— 完成 A 实现计划后再视流量/基线数据决定是否落地 B。本文件冻结存档，不再为实现依据；HARD-GATE 未解除时整体不实现）
> 范围：在 RAG 检索链路「入口 → 多路召回」之间插入一个可关闭的 Query 理解层，把"原样进检索的用户问题"升级为"规范化 + 关键词化 + （后续）改写/消解后的检索输入"，并为其提供统一承载位与可观测产物。
> 前置澄清：本层不改变现有检索器/融合/重排/落库的对外语义；`enabled=false`（默认）时全链路行为与现状逐字节等价。
> v1.1 重要更正：v1.0 主张的"中文 2-gram 构造 tsquery（OR）兜底全文路"经真机实测**不成立**（§2.1 E1/E2），已撤销并改为 ILIKE 子串召回；同时发现**模糊路对中文同样恒不命中**（E3/E3b/E4），故本层真正的召回收益论证需按 §3.4 重新界定。

---

## 1. 目标

1. 给"用户原始问题"与"多路召回"之间补一个**可关闭、可观测、租户安全**的理解层，修复当前"query 原样进检索"导致的召回损失。
2. 建立**理解产物承载位**（当前 `RagQuery` 无任何承载位），使改写/关键词/意图等产物有唯一落点、可透传、可落库、可评测。
3. 优先用**零新依赖、零外部调用**的规则能力修复"中文全文路 + 模糊路双路 0 命中"（实测 E1–E4：三路混合检索实际退化为纯向量）；LLM 改写/指代消解列为后续里程碑。
4. 与既有 `2026-09-19-toolset-routing-retrieval`（工具/技能召回）、`2026-09-13-hybrid-retrieval-workflow`（多路召回编排）职责正交，不重叠。

## 2. 现状回顾（真实机制，均经代码核实）

| # | 现状事实（证据） | 影响 |
|---|---|---|
| C1 | 主链路 `/api/chat` → `ChatController.chat:136/140` → `RagAgentService`（ReAct）→ LLM 自主决定把字符串传给 `KnowledgeBaseTool.searchKnowledgeBase`，`KnowledgeBaseTool:82` `query.setQuery(question)` **原样**进 `RagSearchService.search`。 | query 文本全程零加工；唯一"改写"是 LLM 自由填参，不可观测、不落库 |
| C2 | `RagSearchServiceImpl` 三个公开入口 `search:51`/`streamAnswer:146`/`retrieve:195` 内，`hybridRetrieve:208` 直接按策略把 `query.getQuery()` 原样传给三路检索器；唯一的"规范化"是 `buildCacheKey:226` 的 `query.getQuery().trim().toLowerCase()`（仅服务缓存 key，检索/rerank/prompt 仍用原文）。 | 口语化/错别字/噪声词/超长 query 直接进检索与 LLM |
| C3 | 多路召回节点 `VectorRetrieveNode:47`、`FullTextRetrieveNode`、`FuzzyRetrieveNode` 各自 `query.getQuery()` 原样取出，且 `TOP_K` 硬编码（向量/全文 50、模糊 30）。 | 无法按 query 特征自适应；理解产物无消费点 |
| C4 | `FullTextRetriever.buildTsQuery:59`：正则 `[a-zA-Z0-9]+` 抽 ASCII 词做前缀匹配，**英文词之间的中文段整块当作一个 term**，多词用 `&`（AND）连接；底层 `to_tsquery('pg_catalog.simple', …)` 对中文不分词（文档侧 `content_tsv` 由 `TenantServiceImpl:92` 的 `tsvector_update_trigger(…, 'pg_catalog.simple', content)` 生成）。 | 中文长句全文路必然 0 命中（见 §2.1 实测 E1） |
| C9 | `FuzzyRetriever:38` 用 `similarity(content, ?) > 0.1` 过滤；`pg_trgm` 对 UTF-8 中文按字节切三元组，短中文查询与长文档的 `similarity`/`word_similarity` 实测恒为 0（见 §2.1 实测 E3/E4）。 | 中文场景下模糊路同样恒不命中 → **全文路 + 模糊路双路失效** |
| C5 | `RagQuery`（49 行）字段中 `documentIds`/`vectorWeight`/`keywordWeight`/`stream` 为死字段，**无任何理解产物承载位**（无关键词/意图/原文备份类字段）。 | 理解层无处写入 |
| C6 | 入口对 `query` 无合法性/长度校验；`RagQuery.query` 为 null 时 `buildCacheKey:226` 直接 NPE。 | 空/超长/纯符号 query 直接进入 LLM |
| C7 | 意图识别 `router` 包（`IntentRecognizer`/`ChatRouter`）整体 `@Deprecated`，`ChatRouter` 在 `src/main` 零引用 → 运行时意图能力实际为零；意图体系仅 4 类、规则硬编码在构造函数、LLM 分支无熔断。 | 无意图细分、无落库；旧实现不可直接复用 |
| C8 | `rag_session` 无 intent/改写列；`RagSessionMeta` 有 `tags`/`metadata`(jsonb) 可作未来落点。 | 理解产物暂时无处持久化 |

> 结论：当前"理解"事实上外包给了 ReAct LLM 的自由填参，检索链路本身零理解能力。且经真机实测（见 §2.1），**中文场景下全文路与模糊路双双结构性失效，三路混合检索实际退化为纯向量检索** —— 这是 B 要解决的真问题，且修复点必须落在 DB 检索层，不是 query 文本层。

### 2.1 真机实测证据（PG 16.11 / UTF8 / `pg_catalog.simple`，容器 `docker-pgvector-1`）

> 本节是 v1.1 新增。v1.0 的 §3.4 能力②（中文 2-gram + `|` OR tsquery）在实现前经真机验证**不成立**，故 v1.1 撤销该方案并改向。所有结论均为 `psql` 实测输出，非推断。

| # | 实测语句 | 输出 | 结论 |
|---|---|---|---|
| E1 | `to_tsvector('pg_catalog.simple','员工年假报销流程说明')` | `'员工年假报销流程说明':1` | 连续中文 = **整段 1 个词位**，不分词 |
| E2 | 上述 tsv `@@ to_tsquery('simple','年假')` / `@@ to_tsquery('simple','年假:*')` | `false` / `false` | **2-gram 查询词无法命中**（精确与前缀均失败）→ v1.0 能力② 空转 |
| E2b | 上述 tsv `@@ to_tsquery('simple','员工年假报销流程说明')`（整句） | `true` | 仅"整句完全一致"才命中 → 印证 C4：查询词越接近原句越可能命中，任何切分都会失配 |
| E3 | `similarity('员工年假报销流程说明：需提前三天提交申请','年假')` | `0.0000` | 短中文查询 → **恒 0** |
| E3b | `similarity(doc,'年假怎么报销')` | `0.0000` | 正常长度中文查询 → **仍恒 0** |
| E3c | `show_trgm('年假')` / `show_trgm('annual')` | 5 字节派生的 3 个哈希三元组 / `{"  a"," an","al ",ann,…}` | 中文三元组是按字节派生的哈希、**无词义共享**，与英文可共享 `ann/nnu` 的行为根本不同 |
| E4 | `word_similarity('年假怎么报销', doc)` | `0.0000` | `word_similarity` 同样恒 0，不能作替代 |
| E5 | `content ILIKE '%年假%'` | `true`（命中） | **子串匹配是唯一有效的中文关键词路径** |
| E6 | `EXPLAIN SELECT … WHERE content ILIKE '%年假%'` | `Seq Scan on vector_store`（Filter，未用 GIN） | 既有 `gin_trgm_ops` 索引（`TenantServiceImpl:101`）对中文 `ILIKE` **不生效**，走全表扫 |
| E7 | `SELECT count(*) FROM tenant_hufu_tenant.vector_store` | `425` | 当前单租户 chunk 量级下 Seq Scan 代价可接受，但**不具规模保证** |
| E8 | `SELECT content FROM probe WHERE content ILIKE '%年假%' OR content ILIKE '%报销%'`（3 条测试文档，仅 1 条含"年假"） | 命中该条 | 2-gram 喂 `ILIKE` **能命中** → 这才是中文关键词的正确谓词 |
| E9 | 按"命中词数"打分排序：`SELECT id, (SELECT count(*) FROM unnest(ARRAY['年假','报销','java']) t WHERE content ILIKE '%'||replace(replace(t,'\','\\'),'%','\%')||'%') AS hits FROM probe ORDER BY hits DESC` | `1\|2`、`3\|1`、`2\|0` | **命中词数可有效排序**（命中 2 词 > 1 词 > 0 词），无需 `ts_rank` |

**由实测得出的三条设计约束（v1.1 立论基础）**

1. **撤销 v1.0 能力②**：中文 2-gram 构造 tsquery（无论 `&` 还是 `|`）对 `pg_catalog.simple` 生成的 `content_tsv` 一律无法命中，写了也是空转，不得实现。
2. **修复必须双路**：只修全文路（C4）不够，模糊路（C9）在中文下同样恒不命中；二者叠加才是"退化为纯向量"的真实原因。
3. **纯 query 文本层无法解决 C4/C9**：理解层能改善进入向量路的文本质量，但中文关键词召回必须改 DB 谓词（E5 的 `ILIKE`）或改文档侧分词（`zhparser`/`pg_jieba`，需运维改造）。因此本期把"召回修复"从 query 理解层**拆出为独立能力**，理解层退居其上游供给方。


## 3. 架构设计

### 3.1 插入点与总体结构（原则：单点插入、默认旁路）

在 `RagSearchServiceImpl` 三个公开入口（`search`/`streamAnswer`/`retrieve`）调用 `hybridRetrieve` **之前**，统一插入一次理解层调用：

```
入口(query)
  → queryUnderstandingService.enrich(query)   // 规范化覆盖 query 字段 + 抽取 searchTerms；关闭时直接 return
  → hybridRetrieve(query)                      // 三路检索器零改动，读到的即规范化文本；仅全文路透传 searchTerms
  → rerank / finalFilter / prompt（不变）
```

- **单点插入**：理解层只在 `RagSearchServiceImpl` 调用一次，三路检索器/融合/重排/落库/Prompt 全部不感知"理解层存在"。避免在 workflow 多个节点重复触发。
- **规范化落点（v1.1 关键更正）**：默认 HYBRID 路径经 `HybridRetrievalWorkflow` 节点，节点从 state 读 `RagQuery` 后取 `query.getQuery()`（`VectorRetrieveNode:46-47`），**不会读新字段**。故规范化结果**直接覆盖写回 `query` 字段本身**（`query.setQuery(normalized)`），下游节点/rerank/prompt/缓存 key 全部零改动自动生效。覆盖前的原值存入新增字段 `originalQuery`（供回退、落库与可观测）。
- **唯一需透传的产物是 `searchTerms`**：仅全文路消费，需 `FullTextRetrieveNode` 从 `RagQuery` 读出并传给 `FullTextRetriever` 重载（见 §3.4、§6）。
- **默认旁路**：`rag.query.enabled=false`（默认）时 `enrich` 第一行 `return`，`query` 字段不被覆盖、`searchTerms` 保持 `null`，下游全走原文 → 行为与现状逐字节等价（见 §3.6）。
- **覆盖范围**：因所有 RAG 检索入口（`/api/chat` 经 `KnowledgeBaseTool`、`/api/rag/search`、`/api/rag/stream`）最终都汇聚到 `RagSearchServiceImpl`，单点插入即覆盖全部 RAG 链路，无需改各 Controller/Tool。
- **覆盖范围的诚实边界（v1.1 明确）**：`/api/chat` 主链路进入本层的**不是用户原话，而是 ReAct LLM 自主填写的 `question`**（`KnowledgeBaseTool:82`）。因此对主链路，能力①（规范化/截断）主要防 LLM 填入超长/空值，能力②（关键词召回）修的是"LLM 转述后的 query 与文档用词不一致"，**对用户口语化/错别字的直接修复效果有限**（那些多已被 LLM 转述掉）。真正直接受益的是 `/api/rag/search`、`/api/rag/stream` 这类 query 直连入口。此边界记为 §7 R10。

### 3.2 理解产物承载位（扩展 `RagQuery`，向后兼容）

在 `RagQuery` 新增字段（均默认 `null`，不改既有字段语义）：

| 新字段 | 类型 | 含义 | 消费方 |
|---|---|---|---|
| `searchTerms` | `List<String>` | 抽取出的检索关键词（含中文 2-gram） | 全文路：`FullTextRetrieveNode` 透传给 `FullTextRetriever` 重载（非空走 ILIKE 子串，见 §3.4） |
| `originalQuery` | `String` | 规范化**前**的原始 query 备份 | 可观测/回退；`query` 字段被规范化覆盖后仍可从 `originalQuery` 取回原文 |
| `intentHint` | `String` | 意图提示（M3 才写入，M1/M2 恒 null） | 预留，本期不消费 |

> 说明：规范化文本**直接写回既有 `query` 字段**（不新增 `rewrittenQuery` 作下游源，避免 workflow 节点不读新字段的死角）；`originalQuery` 存原文备份。`searchTerms` 是 M1 唯一需显式透传的产物。缓存 key 因 `query` 字段已被规范化覆盖，`buildCacheKey:226` 天然读到规范化文本，无需改动读取源（见 §7 R1 更正）。


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
- `query` 为 `null`/空白 → 置 `query.setQuery("")`；入口据此**短路返回空结果**（不再 NPE、不再打 LLM）。
- 超长截断：规范化后长度超过 `rag.query.max-length`（默认 512）则截断，避免超长 query 打爆 embedding/token。
- 首尾空白、连续空白归一。
- **落点**：规范化结果覆盖写回 `query` 字段；覆盖前把原值存入 `originalQuery`。因覆盖会污染"落库用的 query"，Controller 落库侧的取值口径见 §7 R9（必须落 `originalQuery` 原文）。

**能力②：中文关键词子串召回（治 C4，并补偿 C9 失效带来的召回缺口；v1.1 依实测 E1–E9 重写）**

- 从规范化后的 `query` 抽取 `searchTerms`：ASCII 词（沿用现有 `[a-zA-Z0-9]+`）+ 中文按 **2-gram 滑动切分**（零依赖，无需引入 jieba/HanLP），并截断至 `rag.query.max-terms`（默认 8）词上限。词抽取逻辑与 v1.0 相同，**改变的是这些词喂给 DB 的谓词**。
- **关键更正（相对 v1.0）**：v1.0 计划把 terms 以 `|` OR 喂给 `to_tsquery` —— 实测 E2 证明 2-gram 喂 tsquery 对 `content_tsv` 一律不命中（文档侧整段是 1 个词位），**该路作废**。改为把 terms 喂给**子串匹配** `content ILIKE '%term%'`（实测 E5 证明唯一有效）。
- `FullTextRetriever` 增加**重载** `retrieve(String query, List<String> terms, int topK)`：
  - `terms` 非空 → 走**中文子串召回**新 SQL：`WHERE (content ILIKE ? OR content ILIKE ? OR …)`，`SELECT` 打分用"命中词数"（实测 E9：doc 命中 2 词 > 命中 1 词 > 命中 0 词，排序有效），`ORDER BY 命中数 DESC LIMIT ?`；term 全部走 `?` 占位符（与既有 `FullTextRetriever:46`/`FuzzyRetriever:42` 的 `queryForList(sql, tsQuery, tsQuery, topK)` 风格一致）。
  - `terms` 为空 → 回退原 `buildTsQuery(query)` 全文 SQL（**旧英文/数字行为完全保留**，向后兼容）。
  - **转义铁律**：term 在 **Java 侧绑定前**转义 `\`、`%`、`_`（顺序：先 `\` 再 `%`/`_`），再包成 `%term%` 作为 `?` 参数值 —— **不做任何 SQL 字符串拼接**（防注入）。实测 E9 用 SQL 端 `replace` 等价验证了转义必要性与有效性。
- 模糊路（C9）：`FuzzyRetriever` 的 `similarity>0.1` 对中文恒 0（实测 E3/E3b/E4）。本期**不改动模糊路**（改动风险大、且子串召回已覆盖中文关键词需求），仅在 §7 R7 记为观察项；若后续要救模糊路，方向是 `word_similarity` + 更低阈值或 `ILIKE` 兜底，单列评估。
- 精度保护：子串 OR 扩大召回后，仍由既有 `CrossEncoderReranker` + `ResultFilter` 收敛，不额外改排序。
- 向量路/rerank/prompt：因 `query` 字段已被规范化覆盖，全部零改动自动读到规范化文本；模糊路同样读 `query`（不改代码，本期不特殊处理 C9）。

> 为什么 2-gram + ILIKE 而非引分词库：零新依赖、零运维。实测证明"2-gram 喂 tsquery"不成立（E2），"2-gram 喂 ILIKE 子串"成立且排序有效（E8/E9）。中文关键词召回的最小有效修复即"子串命中词数排序"。专业分词（jieba）或 DB 侧 `zhparser`/`pg_jieba` 列为 §8 后续。

> 性能边界（实测 E6/E7）：中文 `ILIKE '%term%'` 不走既有 `gin_trgm_ops` 索引（走 Seq Scan）。当前单租户 ~425 chunk 量级可接受；数据量增长后需评估：(a) 限制参与子串匹配的 term 数上限（`rag.query.max-terms`，默认 8），(b) 或引入表达式索引/pg_jieba。见 §7 R8。


### 3.5 后续里程碑（本期不做，见 §8）

- **M2 LLM 查询改写 + 指代消解**：结合 `RagChatMemory` 历史，用 LLM 把"它的第二步是什么"补全为自包含 query；**必须** `@CircuitBreaker(name="query-rewrite")` + `@RateLimiter`（遵守铁律：外部 LLM 调用必须熔断），降级回退"不覆盖 `query` 字段"。
- **M3 意图细分与路由**：重写（非复用 `@Deprecated` 的 `router` 包）意图层，产出结构化 `intentHint` 并落 `RagSessionMeta.metadata`。旧 `router` 包处理（删除或改造）单列评估，不在本期。

### 3.6 兼容性铁律（enabled=false 必须逐字节等价现状）

1. `enabled=false`（默认）：`QueryUnderstandingService` 不装配 → `RagSearchServiceImpl` 注入为 `null` → 入口判空跳过 → `RagQuery` 新字段全 `null`。
2. 下游回退路径：`enrich` 未执行 → `query` 字段未被覆盖、`searchTerms` 为 `null` → 三路检索器与缓存 key 天然读到原文，`FullTextRetriever` 走原 `buildTsQuery`。二者叠加使关闭态检索输入与现状完全相同。
3. `FullTextRetriever` 旧方法 `retrieve(String,int)` 保留不动，新方法为重载，不破坏既有调用与测试。

## 4. 数据流

```
用户 query
  │
  ▼
RagSearchServiceImpl.search / retrieve / streamAnswer
  │
  ├─(A) queryUnderstandingService != null ? enrich(query) : 跳过
  │       ├─ 规范化：null/空白→短路空结果；超长截断；空白归一 → 覆盖 query 字段，原值存 originalQuery  [C6]
  │       └─ 关键词：ASCII 词 + 中文 2-gram（≤max-terms）→ query.searchTerms  [C4/C9]
  │
  ├─(B) hybridRetrieve(query)
  │       ├─ 向量路：VectorRetriever.retrieve(query.getQuery(), 50)   // 已规范化，节点零改动
  │       ├─ 全文路：FullTextRetrieveNode → FullTextRetriever.retrieve(query.getQuery(), searchTerms, 50)
  │       │            // searchTerms 非空 → ILIKE 子串 + 命中词数排序；空 → 旧 buildTsQuery
  │       └─ 模糊路：FuzzyRetriever.retrieve(query.getQuery(), 30)    // 本期不改，中文恒 0（C9/R7）
  │
  ├─(C) rerank(query.getQuery(), …) → finalFilter → prompt（均零改动，读到的是规范化文本）
  │
  └─(D) buildCacheKey 仍读 query.getQuery()（已被规范化覆盖，天然一致，见 §7 R1 更正）
```

执行顺序钉死：**先 enrich（判空可短路）→ 再检索**。短路发生在任何外部调用之前。

## 5. 测试策略（最窄范围）

- `QueryUnderstandingServiceImplTest`（纯单测，无 Spring）：
  - 正常：中英混合 query → `searchTerms` 含 ASCII 词与中文 2-gram、长度 ≤ `max-terms`；`query` 字段已规范化、`originalQuery` 为原文。
  - 边界：长度恰好 `max-length` / 超 `max-length` 截断；单字中文（2-gram 退化，不产出中文 term）；纯 ASCII；纯符号；term 数恰好等于 `max-terms`。
  - 异常：`query=null` → `query` 置空串并标记短路；全空白 → 短路。
- `FullTextRetrieverTest`（mock `JdbcTemplate`）：
  - `terms` 非空 → `verify(jdbc).queryForList(sqlCaptor…)`，断言 SQL 含 `ILIKE` 且**不含** `to_tsquery`；断言 term 以 `?` 占位传参、参数数组含转义后的 `%年假%`（`%`/`_`/`\` 转义生效）。
  - `terms` 空 → 断言 SQL 含 `to_tsquery` 且 `&`（旧 AND 行为），走原占位数量（2 个 tsQuery + 1 个 topK）。
  - 两条路径都断言 `LIMIT` 参数等于入参 `topK`。
- `RagSearchServiceImplTest`：`queryUnderstandingService=null`（关闭态）→ 检索调用参数与现状一致（回归保护）；开启态断言 `query.getQuery()` 已被规范化覆盖。
- **中文召回的真机验证（不可用 mock 替代）**：因 §2.1 全部结论来自真库，能力② 的有效性必须在真实 PG 容器上验证 —— 造含"员工年假报销流程说明"的 chunk，断言 `retrieve("年假怎么报销", terms=["年假","报销"], topK)` 返回该 chunk。**mock 只能验证 SQL 结构，不能证明中文命中**。
- 运行（scoped）：`mvn -pl company-rag-rag -am test -Dtest=QueryUnderstandingServiceImplTest,FullTextRetrieverTest,RagSearchServiceImplTest`。

## 6. 改动清单

| 文件 | 改动 | 模块 |
|---|---|---|
| `rag/model/RagQuery.java` | 新增 `searchTerms`/`originalQuery`/`intentHint` 三字段（默认 null） | rag |
| `rag/query/QueryUnderstandingService.java` | 新增接口 | rag |
| `rag/query/QueryUnderstandingServiceImpl.java` | 新增实现，类级 `@ConditionalOnProperty(rag.query.enabled=true)` | rag |
| `rag/retriever/impl/FullTextRetriever.java` | 新增重载 `retrieve(String,List<String>,int)`（terms 非空走 ILIKE 子串 + 命中词数排序），旧方法不动 | rag |
| `rag/workflow/FullTextRetrieveNode.java` | 从 state 的 `RagQuery` 读 `searchTerms` 传给新重载；`searchTerms` 为 null 时调旧方法 | rag |
| `rag/service/impl/RagSearchServiceImpl.java` | 三入口前插入 `enrich`（可选注入判空 + 短路）；规范化覆盖 `query` 字段 | rag |
| `web/controller/ChatController.java` | 仅 `/api/rag/search` 落库处（`:269`）改读 `query.getOriginalQuery() ?: query.getQuery()`，避免规范化文本污染会话原话（见 §7 R9） | web |
| `application.yml` | 新增 `rag.query` 基段：`enabled: false`、`max-length: 512`、`max-terms: 8` | bootstrap |

> 不改 `/api/chat` 主链路 Controller 逻辑（其落库用的是用户原始 message，不经 `RagQuery`，不受覆盖影响）、不改 `KnowledgeBaseTool`（其 `recordAudit` 用的是 LLM 传入的 `question` 局部变量，同样不受影响）、不改向量/模糊检索节点、不改融合/重排/落库/Prompt 逻辑。`intentHint` 仅占位不消费。`VectorRetrieveNode`/`FuzzyRetrieveNode` 的 `TOP_K` 硬编码（C8）本期不动，列 §8。

## 7. 风险与观察项

- **R1（更正）缓存 key 无需改读取源，但需版本失效机制**：因规范化直接覆盖 `query` 字段，`buildCacheKey:226` 的 `query.getQuery()` 天然读到规范化文本，v1.0 担心的"改写后仍命中旧缓存"不存在。**残留风险**：规范化/切词策略一旦调整（改 `max-length`、2-gram 改分词库），历史缓存语义漂移 —— 需 bump `RagCacheManager` 缓存版本号。**版本由谁 bump、何时 bump 本期钉死为**：`rag.query.enabled` 从 false 改 true、或 `max-length`/`max-terms` 变更时，由运维手动 bump 版本号（本期不做自动 bump）；实现计划须写明该操作项。
- **R2 召回精度**：2-gram + ILIKE 子串 OR 会扩大全文召回，可能引入噪声 chunk。缓解：仅影响全文一路，最终由 rerank + `maxPerDoc` 收敛；`max-terms` 限制参与匹配的 term 数。
- **R3 短路语义变更**：M1 让 null/空白 query 返回空结果而非抛 NPE。这是**有意的行为改进**，但改变了现状（现状是 500）。需在实现计划确认前端对空结果的处理；对 `/api/chat` 主链路无影响（其经 ReAct，空 query 通常 LLM 直接澄清）。
- **R4 铁律合规**：M1 零外部 LLM 调用，不触发"外部调用必须熔断"铁律；M2 引入 LLM 改写时**必须**先加 `@CircuitBreaker`/`@RateLimiter` 再合入。
- **R5 依赖方向**：理解层落在 rag 模块，仅依赖 rag 内部 + common，不新增跨模块依赖，符合边界。
- **R6 向后兼容**：`FullTextRetriever` 用重载而非改签名，`RagQuery` 用新增字段而非改语义，确保既有调用/测试/序列化不受影响。
- **R7 模糊路中文恒 0（观察项）**：实测 E3/E3b/E4 证明 `FuzzyRetriever` 的 `similarity>0.1` 对正常中文查询恒不命中。本期**不修复**（改动风险大、子串召回已覆盖中文关键词需求），仅记录。若后续要救，方向为 `word_similarity` + 低阈值或并入 ILIKE 兜底，单列评估。
- **R8 ILIKE 不走索引（性能）**：实测 E6 证明中文 `ILIKE '%term%'` 走 Seq Scan，既有 `gin_trgm_ops`（`TenantServiceImpl:101`）对中文 ILIKE 不生效。当前 ~425 chunk/租户可接受（E7）；数据量增长后需 `max-terms` 限流或引入 `pg_jieba` + 表达式索引。
- **R9 落库污染（覆盖 query 字段的副作用）**：规范化覆盖 `query` 字段后，`ChatController:269`（`/api/rag/search` 带 sessionId 时）落库用的 `query.getQuery()` 会变成规范化文本、而非用户原话。缓解：该处落库应改读 `originalQuery?:query`（落原文）。本期实现计划须包含此改动点，否则 `rag_session` 历史与 faithfulness 评估看到的是规范化文本。
- **R10 收益边界（诚实声明）**：本层召回收益（能力②）**直接受益方是 query 直连入口**（`/api/rag/search`、`RagController` 的 `/api/rag/stream`、`/api/rag/retrieve`，注意该类已 `@Deprecated`）；`/api/chat` 主链路进本层的是 LLM 转述后的 `question`，规范化/关键词修复的是"LLM 转述文本 vs 文档用词"的错配，而非用户原话的口语化/错别字。**若线上流量绝大多数走 `/api/chat`，能力②的实测收益可能低于预期** —— 实现前建议先统计两路入口的流量占比与全文路命中率基线，再决定是否值得上线。

## 8. 后续演进（本期不做）

- **M2**：LLM 查询改写 + 基于 `RagChatMemory` 历史的指代消解/follow-up 补全（带熔断）。
- **M3**：意图细分与自适应路由；结构化 `intentHint` 落 `RagSessionMeta.metadata`；评估是否删除 `@Deprecated` 的 `router` 包。
- 引入专业中文分词（jieba/HanLP）替换 2-gram，或引入 PG `zhparser`/`pg_jieba` 扩展改善 `content_tsv` 侧（需运维改造）。
- Query 理解效果的离线评测基线（当前 `RetrievalEvalRunner` 不覆盖改写维度）。

## 附录：配置键清单

| 键 | 默认值 | 语义 |
|---|---|---|
| `rag.query.enabled` | `false` | 理解层装配门控；`false`=不装配 Bean、全链路等价现状 |
| `rag.query.max-length` | `512` | 规范化后 query 的截断上限（字符）；键缺失取 512，键存在但空白视为配置错误启动即失败 |
| `rag.query.max-terms` | `8` | `searchTerms` 参与 ILIKE 子串匹配的 term 数上限，限制 Seq Scan 谓词规模（见 §7 R8） |

## 附录：变更日志

| 版本 | 变更 |
|---|---|
| v1.0 | 初版：单点插入 `RagSearchServiceImpl`、`RagQuery` 新增承载位、M1 能力② 采用"中文 2-gram + `\|` OR tsquery"、缓存 key 改用 `rewrittenQuery`。 |
| v1.1 | **依真机实测（§2.1 E1–E7）撤销能力②的 tsquery 方案**，改为"2-gram + `content ILIKE '%term%'` 子串匹配 + 命中词数排序"（E2 证明 2-gram 喂 tsquery 恒不命中，E5 证明 ILIKE 有效）；新增 C9（模糊路中文 `similarity` 恒 0，E3/E3b/E4）；**修正 v1.0 遗漏的承重矛盾**：默认 HYBRID 路径经 workflow 节点、节点只读 `query.getQuery()` 不读新字段 → 规范化改为**直接覆盖 `query` 字段**，取消 `rewrittenQuery` 作为下游读取源，新增 `originalQuery` 存原文；`FullTextRetrieveNode` 需透传 `searchTerms`（进 §6 改动清单）；R1 更正为"缓存 key 无需改读取源、仅需版本失效机制"并钉死 bump 责任；新增 R7（模糊路观察）、R8（ILIKE 不走索引）、R9（覆盖 `query` 污染 `ChatController:269` 落库原话）；配置键 `term-extract-enabled` 替换为 `max-terms`；§5 补"中文召回必须真机验证、mock 不能证明命中"；新增 R10（收益边界：`/api/chat` 进本层的是 LLM 转述文本，能力② 直接受益方是 query 直连入口，上线前应先测流量占比与全文路命中基线）。 |
| v1.2 | **用户裁决定案（按助手推荐）**：①模糊路 C9 本期**不修**、只记观察项 R7（维持 §3.4）；②**缓做 B** —— R10 收益边界成立，进入方案A实现计划，完成后再视流量占比/全文路命中基线决定是否落地。本条 spec 已在头部标注"状态：已冻结"（fileModified 时用户确认），不再作为当前实现依据。 |
