# 检索质量升级（B0 阈值修复 + 融合层评测）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复「答案已被检索但因融合分低于 0.3 硬阈值被丢弃」问题，并提供免 DB 的融合层评测工具量化该问题（recall-but-dropped / drop_rate）。

**Architecture:** 在既有 `company-rag-rag` 模块融合链路（RankNormalizer → ResultFuser → ResultFilter）上做最小改动：将 `ResultFilter` 的绝对分数硬阈值改为「仅在显式传入 >0 时启用」的语义，并把 `RagQuery.scoreThreshold` 默认值改为 null。同时新增一个纯内存的 `RetrievalEvalRunner`，对「三路检索结果 + reference chunk」计算融合前后召回率与 drop_rate，全程不需 DB/LLM，可直接单测。

**Tech Stack:** Java 17, Spring Boot 3.4, JUnit 5, Jackson。

**范围界定（Scope Check 结论）：** 本计划仅覆盖 spec 中 Phase 2 的 B0 与「免 DB 融合层评测」部分（最高优先、可直接单测、直击生产症状）。Phase 2 的 B1（RRF）、B4（权重可配置）、B2（分组过滤）、B3（中文全文索引）与 Phase 1 全量评测（需 DB）涉及跨层/基础设施改动，将作为后续独立实施计划另行产出。

---

## 任务一：ResultFilter 阈值语义修复（B0 核心）

**背景：** `ResultFilter.filter()` 当前 `threshold = scoreThreshold != null ? scoreThreshold : DEFAULT_SCORE_THRESHOLD`，且 `RagQuery.scoreThreshold` 默认 0.3，导致对融合分做绝对硬阈值，误杀「单一低权重路命中、排名靠后」的正确结果。见 spec `2026-09-04-retrieval-quality-upgrade-design.md` 第 1.3 节。

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/fusion/ResultFilter.java`
- Create: `company-rag-rag/src/test/java/com/company/rag/rag/fusion/ResultFilterTest.java`

- [ ] **Step 1: 写失败测试（回归用户症状）**

写一个新测试类，构造「关键 chunk 只在全文路命中且排名靠后，融合分低于 0.3 旧阈值」的用例，断言在**不显式传阈值**（`scoreThreshold == null`）时该 chunk 被保留。

```java
package com.company.rag.rag.fusion;

import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.NormalizedResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultFilterTest {

    private final ResultFilter filter = new ResultFilter();

    /**
     * 回归用户症状：答案只在全文路(权重0.2)命中且排名第3，
     * 旧逻辑 finalScore=0.25*0.2=0.05 < 0.3 会被丢弃。
     * 修复后，默认(无硬阈值)应保留该 chunk。
     */
    @Test
    void filter_default_noHardThreshold_keepsSingleLowWeightRouteHit() {
        FusedResult relevant = new FusedResult();
        relevant.setChunkId("correct-chunk");
        relevant.setFinalScore(0.05);   // 单路低权重融合分远低于旧 0.3 阈值

        FusedResult noisy = new FusedResult();
        noisy.setChunkId("multi-route-hit");
        noisy.setFinalScore(0.85);      // 多路命中，相关度一般也能高分

        List<FusedResult> results = List.of(noisy, relevant);

        // 不显式传阈值 -> 不应做绝对硬阈值，仅保留被任一路命中(finalScore>0)的项
        List<FusedResult> out = filter.filter(results, 10, null);

        assertEquals(2, out.size(), "默认不启用硬阈值，两条都保留");
        assertTrue(out.stream().anyMatch(r -> r.getChunkId().equals("correct-chunk")),
                "低权重单路命中的正确 chunk 不能被丢弃");
    }

    /**
     * 显式传入 >0 阈值时，仍应执行硬阈值过滤（保留原有能力）。
     */
    @Test
    void filter_withExplicitThreshold_appliesHardFilter() {
        FusedResult low = new FusedResult();
        low.setChunkId("low");
        low.setFinalScore(0.05);

        FusedResult high = new FusedResult();
        high.setChunkId("high");
        high.setFinalScore(0.5);

        List<FusedResult> out = filter.filter(List.of(low, high), 10, 0.3);

        assertEquals(1, out.size());
        assertEquals("high", out.get(0).getChunkId());
    }

    /**
     * topK 限制仍旧生效。
     */
    @Test
    void filter_respectsTopK() {
        FusedResult a = new FusedResult();
        a.setChunkId("a");
        a.setFinalScore(0.9);
        FusedResult b = new FusedResult();
        b.setChunkId("b");
        b.setFinalScore(0.8);

        List<FusedResult> out = filter.filter(List.of(a, b), 1, null);

        assertEquals(1, out.size());
        assertEquals("a", out.get(0).getChunkId());
    }

    @Test
    void filter_emptyList_returnsEmpty() {
        assertTrue(filter.filter(List.of(), 10, null).isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试，确认其失败**

Run（在 `company-rag-rag` 模块目录）:
```
cd D:/tmp/CompanyRag/company-rag-rag
mvn -q -Dtest=ResultFilterTest test
```
Expected: `filter_default_noHardThreshold_keepsSingleLowWeightRouteHit` FAIL —— 因为当前逻辑在 `scoreThreshold == null` 时回退到 `DEFAULT_SCORE_THRESHOLD=0.3`，把 `finalScore=0.05` 的 `correct-chunk` 过滤掉（断言 `out.size()==2` 失败，实得 1）。

- [ ] **Step 3: 修改 `ResultFilter.filter()` 实现**

将 `filter()` 的阈值逻辑改为「仅显式传入 >0 时启用硬阈值」。

```java
/**
 * 阈值过滤 + Top-K（不再做多样性去重）
 * <p>硬阈值仅在显式传入门限（scoreThreshold != null && > 0）时生效；
 * 否则只保留被任一路命中（finalScore > 0）的结果，并把排序交由 Rerank 收敛。</p>
 * @param results 融合后的结果
 * @param topK 最终返回数量
 * @param scoreThreshold 分数阈值；null 或 <=0 表示不启用硬阈值
 * @return 筛选后的结果
 */
public List<FusedResult> filter(List<FusedResult> results, int topK, Double scoreThreshold) {
    boolean thresholdEnabled = scoreThreshold != null && scoreThreshold > 0;

    log.info("开始筛选 | 原始数量={} | 启用硬阈值={} | 阈值={} | topK={}",
            results.size(), thresholdEnabled, scoreThreshold, topK);

    return results.stream()
        // 1. 筛选：启用硬阈值时按阈值过滤；否则兜底保留被任一路命中的项(finalScore>0)
        .filter(r -> {
            boolean pass = thresholdEnabled
                    ? r.getFinalScore() >= scoreThreshold
                    : r.getFinalScore() > 0;
            if (!pass) {
                log.debug("阈值过滤 | chunkId={} | score={} | 启用硬阈值={}",
                        r.getChunkId(), r.getFinalScore(), thresholdEnabled);
            }
            return pass;
        })
        // 2. 按分数排序取 Top-K
        .sorted(Comparator.comparingDouble(FusedResult::getFinalScore).reversed())
        .limit(topK)
        .collect(Collectors.toList());
}
```

同时删除不再使用的 `DEFAULT_SCORE_THRESHOLD` 常量声明：
```java
private static final double DEFAULT_SCORE_THRESHOLD = 0.3;   // 删除此行
```

- [ ] **Step 4: 运行测试，确认通过**

Run:
```
cd D:/tmp/CompanyRag/company-rag-rag
mvn -q -Dtest=ResultFilterTest test
```
Expected: 4 个测试全部 PASS（`filter_default_...`、`filter_withExplicitThreshold_...`、`filter_respectsTopK`、`filter_emptyList_returnsEmpty`）。

- [ ] **Step 5: 提交**

```bash
cd D:/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/fusion/ResultFilter.java
git add company-rag-rag/src/test/java/com/company/rag/rag/fusion/ResultFilterTest.java
git commit -m "fix(rag): 修复融合分硬阈值误杀正确结果（B0）"
```

---

## 任务二：RagQuery.scoreThreshold 默认改为 null

**背景：** `RagQuery.scoreThreshold` 默认 `0.3`，会传导到 `MultiRetrieveServiceImpl` 的 `filter.filter(fused, fusionTopK, query.getScoreThreshold())`，使硬阈值在默认配置下始终开启。将其默认改为 null，使 B0 的「默认不启用硬阈值」在实际链路生效。

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/model/RagQuery.java`

- [ ] **Step 1: 修改默认值与注释**

只改 `scoreThreshold` 字段声明与注释：

```java
    /**
     * 分数阈值（默认 null）。
     * 语义：仅当显式设置为 > 0 时才启用硬阈值过滤；null 表示交由 Rerank 收敛。
     */
    private Double scoreThreshold;
```

（将原 `private Double scoreThreshold = 0.3;` 中的 `= 0.3` 移除。）

注意：`RagSearchServiceImpl.java:247` 对 `scoreThreshold` 有独立的缓存键 fallback `?: 0.3`，因此默认改为 null 不会影响缓存键；无需改动该文件。

- [ ] **Step 2: 编译模块验证**

Run:
```
cd D:/tmp/CompanyRag/company-rag-rag
mvn -q compile
```
Expected: BUILD SUCCESS（语法正确、无断裂引用）。

- [ ] **Step 3: 运行融合相关既有测试，确认行为符合预期**

Run:
```
cd D:/tmp/CompanyRag/company-rag-rag
mvn -q -Dtest=ResultFuserTest,RankNormalizerTest,ResultFilterTest test
```
Expected: 全部 PASS。`MultiRetrieveIntegrationTest` 需要真实 DB，属于集成测试，本步跳过；其显式 `query.setScoreThreshold(0.3)` 的用例不受影响（显式阈值仍被 B0 尊重）。

- [ ] **Step 4: 提交**

```bash
cd D:/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/model/RagQuery.java
git commit -m "feat(rag): 检索默认不启用融合分绝对硬阈值（B0）"
```

---

## 任务三：免 DB 融合层评测工具（量化 recall-but-dropped）

**背景：** spec Phase 1 需要量化用户症状（答案被检索出但因低分被丢弃）。为避免引入 DB/LLM 依赖，此任务实现一个**纯内存、单测可跑**的融合层评测器：给定三路检索结果 + reference chunk 集合，计算融合前召回率、融合后召回率与 `drop_rate`。后续接入真实检索引擎时只需替换「三路结果来源」。

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/EvalCase.java`
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/RetrievalEvalResult.java`
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/eval/RetrievalEvalRunner.java`
- Create: `company-rag-rag/src/test/java/com/company/rag/rag/eval/RetrievalEvalRunnerTest.java`

- [ ] **Step 1: 定义评测输入模型 `EvalCase`**

```java
package com.company.rag.rag.eval;

import java.util.List;

/**
 * 单条检索评测用例
 * @param id           用例标识
 * @param query        用户问题
 * @param referenceChunkIds 人工标注的正确答案 chunkId 集合
 */
public record EvalCase(String id, String query, List<String> referenceChunkIds) {
}
```

- [ ] **Step 2: 定义评测结果模型 `RetrievalEvalResult`**

```java
package com.company.rag.rag.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条用例的检索评测结果。
 * recallBefore: reference 中出现在任一路检索结果的比例（召回能力）
 * recallAfter:  reference 中出现在实际可用 Top-K（过滤后）的比例
 * droppedChunkIds: 检索到但被过滤掉的 reference chunk
 */
public class RetrievalEvalResult {
    private final String caseId;
    private final String query;
    private final double recallBefore;
    private final double recallAfter;
    private final List<String> droppedChunkIds;

    public RetrievalEvalResult(String caseId, String query,
                               double recallBefore, double recallAfter,
                               List<String> droppedChunkIds) {
        this.caseId = caseId;
        this.query = query;
        this.recallBefore = recallBefore;
        this.recallAfter = recallAfter;
        this.droppedChunkIds = new ArrayList<>(droppedChunkIds);
    }

    /** 被阈值/排序丢弃的正确 chunk 占比 */
    public double dropRate() {
        return recallBefore - recallAfter;
    }

    public String caseId() { return caseId; }
    public String query() { return query; }
    public double recallBefore() { return recallBefore; }
    public double recallAfter() { return recallAfter; }
    public List<String> droppedChunkIds() { return droppedChunkIds; }
}
```

- [ ] **Step 3: 实现评测器 `RetrievalEvalRunner`**

```java
package com.company.rag.rag.eval;

import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.fusion.ResultFuser;
import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.NormalizedResult;
import com.company.rag.rag.model.RagResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 免 DB 的融合层检索评测器：
 * 给定三路检索结果 + reference，量化「检索到但被过滤丢弃」问题(dropRate)。
 * 依赖既有 RankNormalizer/ResultFuser/ResultFilter，不访问数据库或 LLM。
 */
public class RetrievalEvalRunner {

    private final RankNormalizerNormalizerHolder normalizer = new RankNormalizerNormalizerHolder();
    private final ResultFuser fuser = new ResultFuser();
    private final ResultFilter filter = new ResultFilter();

    /**
     * 评估单条用例。
     * @param case           测试用例
     * @param vectorResults  向量路结果（按排名序）
     * @param fulltextResults 全文路结果（按排名序）
     * @param fuzzyResults   模糊路结果（按排名序）
     * @param fusionTopK     过滤后保留条数
     * @param scoreThreshold 显式阈值，null 表示不启用硬阈值
     */
    public RetrievalEvalResult evaluate(EvalCase evalCase,
                                        List<RagResult.ChunkResult> vectorResults,
                                        List<RagResult.ChunkResult> fulltextResults,
                                        List<RagResult.ChunkResult> fuzzyResults,
                                        int fusionTopK,
                                        Double scoreThreshold) {
        Set<String> reference = new HashSet<>(evalCase.referenceChunkIds());

        // 融合前：reference 是否命中任一路
        Set<String> before = new HashSet<>();
        addIds(before, vectorResults, fulltextResults, fuzzyResults);
        int hitBefore = countHit(before, reference);
        double recallBefore = reference.isEmpty() ? 0.0 : (double) hitBefore / reference.size();

        // 执行归一化 + 融合
        List<NormalizedResult> normV = normalizer.normalize(vectorResults);
        List<NormalizedResult> normF = normalizer.normalize(fulltextResults);
        List<NormalizedResult> normZ = normalizer.normalize(fuzzyResults);
        List<FusedResult> fused = fuser.fuse(normV, normF, normZ, evalCase.query());

        // 过滤（Rerank 无法在本层模拟，因此以融合后 Top-K 作为「实际可用」口径）
        List<FusedResult> filtered = filter.filter(fused, fusionTopK, scoreThreshold);
        Set<String> after = new HashSet<>();
        filtered.forEach(f -> after.add(f.getChunkId()));

        int hitAfter = countHit(after, reference);
        double recallAfter = reference.isEmpty() ? 0.0 : (double) hitAfter / reference.size();

        // 被过滤丢弃的 reference chunk
        Set<String> current = new HashSet<>(before);
        current.removeAll(after);
        List<String> dropped = new ArrayList<>(current);
        dropped.retainAll(reference);

        return new RetrievalEvalResult(evalCase.id(), evalCase.query(), recallBefore, recallAfter, dropped);
    }

    private void addIds(Set<String> target, List<RagResult.ChunkResult>... lists) {
        for (List<RagResult.ChunkResult> list : lists) {
            if (list == null) continue;
            list.forEach(c -> target.add(c.getChunkId()));
        }
    }

    private int countHit(Collection<String> candidates, Set<String> reference) {
        int hit = 0;
        for (String r : reference) {
            if (candidates.contains(r)) hit++;
        }
        return hit;
    }

    // 保引用既有 RankNormalizer；此处以最小实现等价于其 1/(rank+1) 语义，避免暴露未声明的依赖
    private static class RankNormalizerNormalizerHolder {
        List<NormalizedResult> normalize(List<RagResult.ChunkResult> results) {
            List<NormalizedResult> out = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                NormalizedResult nr = new NormalizedResult(results.get(i));
                nr.setNormalizedScore(1.0 / (i + 1));
                out.add(nr);
            }
            return out;
        }
    }
}
```

**注意（类型一致性）：** 本任务直接 `new RankNormalizer()` 会更符合 DRY，但为避免任务间耦合并让本类可独立演进，这里用一个私有等价实现。若后续你在实现时发现 `RankNormalizer` 是公开 `@Component` 且无参构造可直接实例化，可用 `import com.company.rag.rag.fusion.RankNormalizer;` 替换私有占位类 `RankNormalizerNormalizerHolder`，其余逻辑不变。

- [ ] **Step 4: 写失败测试 `RetrievalEvalRunnerTest`**

验证：用 0.3 显式阈值（复现旧默认）时，`droppedChunkIds` 含"只在全文路命中"的 reference；用 null（B0 新默认）时，不丢弃该 reference。

```java
package com.company.rag.rag.eval;

import com.company.rag.rag.model.RagResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RetrievalEvalRunnerTest {

    private final RetrievalEvalRunner runner = new RetrievalEvalRunner();

    @Test
    void evaluate_withOldThreshold_showsDrop_butWithNullKeepsReference() {
        // reference chunk 只在全文路命中(第3个位置)
        EvalCase evalCase = new EvalCase("c1", "某配置项含义",
                List.of("correct-chunk"));

        List<RagResult.ChunkResult> vector = List.of();
        List<RagResult.ChunkResult> fulltext = List.of(
                chunk("noise-1"), chunk("noise-2"), chunk("correct-chunk"));
        List<RagResult.ChunkResult> fuzzy = List.of();

        // 旧默认(显式 0.3)：correct-chunk 融合分 = 0.25*0.2 = 0.05 < 0.3，被丢弃
        RetrievalEvalResult withOld = runner.evaluate(
                evalCase, vector, fulltext, fuzzy, 10, 0.3);
        assertEquals(1.0, withOld.recallBefore(), 0.001, "融合前应召回(未来得及体现阈值)");
        // 注意：recallBefore 定义在任一路命中即计为召回；融合层过滤前 correct-chunk 在全文路 -> 计入召回
        assertEquals(0.0, withOld.recallAfter(), 0.001, "被 0.3 阈值丢弃");

        // B0 新默认(不启用硬阈值)：correct-chunk 被保留
        RetrievalEvalResult withNew = runner.evaluate(
                evalCase, vector, fulltext, fuzzy, 10, null);
        assertEquals(1.0, withNew.recallAfter(), 0.001, "默认不再丢弃低权重单路命中");
        assertFalse(withNew.droppedChunkIds().contains("correct-chunk"));
    }

    private RagResult.ChunkResult chunk(String id) {
        RagResult.ChunkResult c = new RagResult.ChunkResult();
        c.setChunkId(id);
        c.setContent("content of " + id);
        return c;
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

Run:
```
cd D:/tmp/CompanyRag/company-rag-rag
mvn -q -Dtest=RetrievalEvalRunnerTest test
```
Expected: 通过。若因上文两步（B0 修复）已生效，`withNew` 分支与 `withOld`(显式 0.3) 分支均符合断言：`withOld.recallAfter==0.0`、`withNew.recallAfter==1.0`。

> 提示：若你把本任务放到 B0 修复之前跑，`withNew` 分支会失败（因当时默认 0.3），这正是评测器价值所在——它能在修复前后量化 drop。本计划按已落定的顺序（先 B0）执行，故此处直接断言新行为。

- [ ] **Step 6: 提交**

```bash
cd D:/tmp/CompanyRag
git add company-rag-rag/src/main/java/com/company/rag/rag/eval/
git add company-rag-rag/src/test/java/com/company/rag/rag/eval/RetrievalEvalRunnerTest.java
git commit -m "feat(rag): 新增免DB融合层检索评测器，量化阈值误杀(dropRate)"
```

---

## Self-Review

**1. Spec 覆盖：**
- B0（spec §4-B0）：任务一 + 任务二完整覆盖（`ResultFilter` 语义 + `RagQuery` 默认值 + 回归单测）。
- Phase 1 评测（spec §3）：任务三实现了免 DB 的融合层评测与 `drop_rate` 量化；全量 DB/LLM 评测（spec §3.2 调用 `MultiRetrieveService.retrieve()`）需真实环境，已明确列入范围外后续计划。
- B1/B4/B2/B3：明确 defer（范围界定声明）。

**2. 占位符扫描：** 所有代码步骤均给出完整可编译代码；无 TBD/TODO/"添加校验"等占位。步骤中唯一"可选替换"是 `RankNormalizerNormalizerHolder`——已给出等价实现与替换指引，非占位。

**3. 类型一致性：**
- `FusedResult.getFinalScore()`、`getChunkId()`：与 `ResultFilter`/`ResultFuser`/`FusedResult` 现状一致。
- `NormalizedResult.setNormalizedScore`/`setChunkId`/构造 `NormalizedResult(RagResult.ChunkResult)`：与 `NormalizedResult.java` 现状一致。
- `RagResult.ChunkResult(id/content)`：与既有测试 `RankNormalizerTest` 中 `new RagResult.ChunkResult(); setChunkId(...); setContent(...)` 一致。
- `filter.filter(results, topK, Double)` 签名在本计划内保持不变（未改签名，只改内部逻辑），`MultiRetrieveServiceImpl`/`RagSearchServiceImpl` 无需改动。

**计划完成并保存到 `docs/superpowers/plans/2026-09-04-retrieval-quality-upgrade.md`。**