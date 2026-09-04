# 检索质量升级设计文档（评测闭环 + 融合阈值修复）

**日期:** 2026-09-04
**状态:** 待用户审批
**基线方案:** `2026-07-26-hybrid-search-optimization-design.md`（已实现）
**前置条件:** 用户确认方向 C（评测闭环先行）→ B（检索工程优化）；明确暂不做 A（查询改写，YAGNI）

---

## 1. 背景与问题根因

### 1.1 现状

当前混合检索链路（源自上文基线方案，均已实现）：

```
三路检索（向量/全文/模糊）→ RankNormalizer 1/(rank+1) 归一化
→ ResultFuser 三档硬编码权重加权叠加
→ ResultFilter finalScore >= 0.3 硬阈值过滤
→ 多样性去重 → Rerank(Cross-Encoder) 精排 → Top-K
```

### 1.2 用户报告的症状

> 「经常日志中看到答案已经被检索出来了，但因为答案的得分比较低，导致答案被丢弃，agent 回复找不到相关问题。」

### 1.3 根因（已勘察确认）

`ResultFilter.filter()` 对**融合后的排名分**做绝对值硬阈值 `finalScore >= 0.3`。而 `finalScore` 是 `1/(rank+1)` 加权叠加的结果，其数值分布完全不具可比性。**真实失败场景：**

- 正确 chunk 只在**单一低权重路**命中且排名靠后 → 融合分极低（如全文路权重 0.2、rank3 → `0.25×0.2=0.05`），被 0.3 阈值误杀；
- 多路重复命中的项即使相关度一般，也能轻松超过 0.3。

**结论：这是排序/阈值侧问题，不是召回侧问题。** 答案已被检索出，却被一个基于融合分绝对值的硬阈值丢弃。

---

## 2. 目标与范围界定

**不做什么（YAGNI，明确定界）：**
- ❌ 方案 A：查询改写 / 意图 / 知识库路由（留待 B 完成后仍有缺口再加）
- ❌ 生成侧 Faithfulness / 引入 LLM 评测打分（保持离线、零额外调用成本）
- ❌ 在此之前不引入 ES/OpenSearch（重量级，先用轻量方案验证）

**要做什么：**

- **Phase 1（C）**：建立可复用检索质量评测闭环，量化「混杂命中率低」与「阈值误杀」，作为 Phase 2 调参的量化依据与回归门禁。
- **Phase 2（B）**：检索工程优化，优先级 B0 → B1 → B4 → B2 → B3。

---

## 3. Phase 1 — 评测闭环（C）

**目标**：建立检索质量基线并量化问题，支撑 Phase 2 的数据驱动调参与回归验证。

### 3.1 评测数据准备（离线脚本）

- 新增 `eval/` 目录（与运行时隔离，非业务代码）。
- `eval/dataset.json`：评测基准集，每条 `{ id, query, reference_chunk_ids, reference_answers }`。
  - 种子来源：现有会话/检索日志抽取高频用户问题；
  - reference_chunk_ids 人工标注正确 chunk（可复用 PGVector 索引导出 + 校验）。
- 覆盖「多知识库混杂」场景：query 仅对应部分文档，验证分组筛选是否有效。

### 3.2 评测执行器

- 新增 `RagEvalRunner`（测试工程或 `eval` 独立模块），对数据集逐条调用既有 `MultiRetrieveService.retrieve()`。
- **关键：记录融合前后两套口径**（直接量化用户症状）：
  - `recall_before`（融合前召回率）：reference chunk 是否出现在任一路 Top-K（召回能力）；
  - `recall_after`（融合后/过滤后召回率）：是否出现在**实际可用的 Top-K**；
  - `drop_rate = recall_before - recall_after`：**被阈值丢弃的正确内容占比**，直接验证 B0 效果。

### 3.3 指标口径（RAGAS 检索侧子集，避免过度工程）

- **Context Recall**：reference chunk 是否被检索到。
- **Context Precision**：相关 chunk 在返回 Top-K 中的排序质量。
- 初次仅 Retrieval 侧指标，不做生成侧 Faithfulness。

### 3.4 产出

- `eval/report/{date}/report.json + .md`：汇总指标、逐条明细、被丢弃的正确 chunk 列表。
- Phase 2 每项改动后重跑同数据集 → 回归对比。

**验证启动器可用本机 Python（D:\programFile\Python）可选做参考实现，核心执行用 Java。**

---

## 4. Phase 2 — 检索工程优化（B）

### B0（最高优先级）— 修复融合分阈值误杀

直击用户症状。改动点：

1. `RankNormalizer`：保持 `1/(rank+1)` 归一化（本身合理，不动）。
2. `ResultFilter.filter()`：**去除 `finalScore >= 0.3` 绝对硬阈值**，改为：
   - 各检索路内部各自保留 Top-K（已由倒排/向量层保证）；
   - 融合后**仅按融合分排序取 Top-K**；
   - 最低门槛降为「**是否被任一路命中**」（`finalScore > 0`）纯兜底；
   - `scoreThreshold` 保留字段，但语义改为「仅显式传入 >0 时才启用」，默认不再过滤。
3. 补 DEBUG 日志：记录被丢弃的低分 chunk（chunkId + 融合分 + 命中路数），供 Phase 1 回溯。
4. 新增单元测试：构造「单路低权重命中 + 高排名」用例，断言不再被丢弃（回归用户 bug）。

**风险**：去阈值可能引入低相关噪声。缓解：依赖 Rerank + `maxPerDoc` 多样性去重 + `finalFilter` 收敛，并用 Phase 1 评测验证。

### B1 — RRF 融合（在 B0 之上增强排序质量）

- `RankNormalizer` 归一化分改为 RRF score：`1/(k + rank)`（k 常数，如 60），对高排名更平滑、多路并列更稳。
- `ResultFuser` 权重改为可配置（与 B4 呼应），仍按权重合并。
- 用 Phase 1 评测验证：是否提升 Context Precision 且不牺牲 Recall。

### B2 — 文档 / 知识库分组筛选

> 现状无「知识库」分组实体，仅 `RagQuery.documentIds`。

- 现在 `VectorRetriever` 的 `SearchRequest` 未使用 `filterExpression` 限制文档 → `documentIds` 实际未生效。修复：
  - `VectorRetriever`：`SearchRequest` 加 `withFilterExpression("documentId in [..]")`；
  - `FullTextRetriever` / `FuzzyRetriever`：SQL 加 `WHERE document_id IN (...)`。
- 知识库分组：先不新增实体，仅在文档元数据支持分组字段，检索入参 `kbIds` → 过滤对应 docId 集合（最小改动、可平滑扩展）。

### B3 — 中文全文索引升级

- 现状：`FullTextRetriever` 用 `pg_catalog.simple` 分词器，中文支持差，且基线设计用的就是它。
- 方案对比：
  - 轻量：接 PG 中文分词扩展（zhparser / pg_jieba）；
  - 重型：引入 Elasticsearch/OpenSearch。
- **推荐**：先轻量（zhparser）验证是否足够（YAGNI）；评测显示不足再评估 ES。

### B4 — 融合权重可配置 + 按维度可调

- `ResultFuser` 三档硬编码权重 → 注入配置（`app.rag.fusion.weights.*`），运行时可调。
- 支持按 query 特征（短/长/专有名词）预设多套，可覆盖。
- 用 Phase 1 评测对比不同权重下的 Precision/Recall，择优。

### Phase 2 优先级（风险低→高、收益高→低）

**B0 → B1 → B4 → B2 → B3**
- 用户痛点由 B0 直接解决；
- B2/B3 涉及 SQL/索引/实体/组件，改动重、放后，且部分可用评测决定是否需要。

---

## 5. 验证与指标

| 指标 | 说明 | 目标 |
|------|------|------|
| drop_rate（被丢弃正确比例） | 融合前 vs 融合后召回率差值 | B0 后显著下降 → 目标 0 |
| Context Recall | 检索召回能力 | Phase2 后不降或提升 |
| Context Precision | 排序质量 | Phase2 后提升 |
| 响应时间 | 检索链路开销 | B0/B1 改动不显著增加 P99 |

- 每项改动以 Phase 1 数据集做前后回归对比，附 report 输出。

---

## 6. 测试策略

- **单元测试**：`ResultFilter`（含 B0 新筛选逻辑 + 阈值语义）、`RankNormalizer`（RRF）、`ResultFuser`（权重可配置）。
- **回归用例**：B0 专测「单路低权重命中不被丢弃」。
- **评测回归**：Phase 1 数据集重跑，产出对比报告。

---

## 7. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 去除 0.3 阈值引入低相关噪声 | 中 | 依赖 Rerank + maxPerDoc 收敛，评测验证 |
| RRF k 值需调参 | 低 | 参数化，用评测选优 |
| 中文分词扩展需 DBA 权限/部署变更 | 高 | 备份、回滚脚本、先轻量验证 |
| documentIds filterExpression 影响性能 | 中 | 确认走索引，压测观察 P99 |

---

## 8. 验收标准

- [ ] Phase 1 评测闭环可运行，产出 `report.json / .md`，含融合前后双指标与 drop_rate
- [ ] B0：单路低权重命中的正确 chunk 不再被丢弃（单元测试通过）
- [ ] B0 后 drop_rate 降至 0（针对种子数据集）
- [ ] 检索链路改动通过既有 RagSearchService 集成测试
- [ ] 权重/RRF/k/阈值等均参数化可配置
- [ ] B2：`documentIds`/`kbIds` 过滤实际生效（filterExpression + SQL WHERE）
- [ ] 每项改动均有评测前后回归对比

---

**文档状态：** 已完成各分段设计并经用户分段确认（第1段阶段划分、第2段 Phase1、第3段 Phase2 均已认可）。
**下一步：** 用户最终批准后，调用 `writing-plans` 生成实现计划。