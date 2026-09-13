# 混合检索 StateGraph 工作流实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变对外行为的前提下，把 `MultiRetrieveServiceImpl` 内部的确定性混合检索流水线重构成一张 Spring AI Alibaba `StateGraph` 工作流，用于体验图编排效果。

**Architecture:** 保持 `MultiRetrieveService` 接口与 `RagSearchServiceImpl` 上层调用不变，仅将 `MultiRetrieveServiceImpl.retrieve()` 委派给新增的 `HybridRetrievalWorkflow`。工作流由三路并行检索节点（向量/全文/模糊）+ 归一化融合节点 + 最终筛选节点组成，业务逻辑复用现有 `VectorRetriever`、`FullTextRetriever`、`FuzzyRetriever`、`RankNormalizer`、`ResultFuser`、`ResultFilter`。

**Tech Stack:** Java 17、Spring Boot 3.4、Spring AI 1.1 / Spring AI Alibaba Graph Core 1.1.2.0（`StateGraph` / `CompiledGraph` / `OverAllState`）、Reactor（`stream()` 返回 `Flux`）、JUnit 5 + Mockito。

---

## 文件结构

新增（均在 `company-rag-rag` 模块，`workflow` 子包）：

- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/HybridRetrievalState.java` — 图状态载体（纯数据，持有 query、三路结果、融合结果）。
- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/HybridRetrievalWorkflow.java` — 组装并执行图工作流，暴露 `execute(RagQuery)`。
- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/VectorRetrieveNode.java` — 向量检索节点。
- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FullTextRetrieveNode.java` — 全文检索节点。
- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FuzzyRetrieveNode.java` — 模糊检索节点。
- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/NormalizeFuseNode.java` — 归一化 + 融合节点。
- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FilterNode.java` — 最终筛选节点。

修改：

- `company-rag-rag/src/main/java/com/company/rag/rag/service/impl/MultiRetrieveServiceImpl.java` — 内部委派给 `HybridRetrievalWorkflow`。

测试（均为单元测试，JUnit 5 + Mockito，不依赖 PG/Redis/外网）：

- `company-rag-rag/src/test/java/com/company/rag/rag/workflow/HybridRetrievalStateTest.java`
- `company-rag-rag/src/test/java/com/company/rag/rag/workflow/RetrieveNodeTest.java`（vector / fulltext / fuzzy 三个节点共用）
- `company-rag-rag/src/test/java/com/company/rag/rag/workflow/NormalizeFuseNodeTest.java`
- `company-rag-rag/src/test/java/com/company/rag/rag/workflow/FilterNodeTest.java`
- `company-rag-rag/src/test/java/com/company/rag/rag/workflow/HybridRetrievalWorkflowTest.java`

不动：`MultiRetrieveService` 接口、`RagSearchServiceImpl`、Controller、DTO、数据层、`company-rag-bootstrap`。

**命令前缀：** 本模块构建/测试用 `cd company-rag-rag && mvn -q -o test`（离线）或 `mvn -q test`；单测类用 `mvn -q -Dtest=<ClassName> test`。验证范围始终限制在本模块相关测试类，不跑全仓库。

---

## 关键既有组件签名（供各任务引用）

- `VectorRetriever.retrieve(String query, int topK)` → `List<RagResult.ChunkResult>`
- `FullTextRetriever.retrieve(String query, int topK)` → `List<RagResult.ChunkResult>`
- `FuzzyRetriever.retrieve(String query, int topK)` → `List<RagResult.ChunkResult>`
- `RankNormalizer.normalize(List<RagResult.ChunkResult>)` → `List<NormalizedResult>`
- `ResultFuser.fuse(List<NormalizedResult> vector, List<NormalizedResult> fulltext, List<NormalizedResult> fuzzy, String query)` → `List<FusedResult>`
- `ResultFilter.filter(List<FusedResult>, int fusionTopK, Double scoreThreshold)` → `List<FusedResult>`；`ResultFilter.finalFilter(List<RagResult.ChunkResult>, int topK, int maxPerDoc)` → `List<RagResult.ChunkResult>`

图 API（来自 `spring-ai-alibaba-graph-core` 1.1.2.0）：
- `StateGraph`（无参构造可用）、`.addNode(String, AsyncNodeActionWithConfig)`、`.addEdge(String, String)`、`.compile()` → `CompiledGraph`。
- `OverAllState.updateState(Map<String,Object>)`、`<T> Optional<T> value(String)`。
- Node 动作类型：`AsyncNodeActionWithConfig<T> extends Function<OverAllState, CompletableFuture<Map<String,Object>>>`。为简化，可让 Node 实现 `AsyncNodeAction`（`AsyncFunction<OverAllState, Map<String,Object>>`，经 `AsyncNodeAction.node_async(...)` 包装）。本计划统一采用 `AsyncNodeAction` 接口，节点以 lambda/方法引用注册。

---

### Task 1: 图状态载体 `HybridRetrievalState`

Establishes the shared state object that flows through the workflow nodes.

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/HybridRetrievalState.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/workflow/HybridRetrievalStateTest.java`
- Depends on: 无

- [ ] **Step 1: Write the failing test**

Create `company-rag-rag/src/test/java/com/company/rag/rag/workflow/HybridRetrievalStateTest.java`:

```java
package com.company.rag.rag.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class HybridRetrievalStateTest {

    @Test
    void initialStateHoldsQueryParams() {
        RagQuery query = new RagQuery();
        query.setQuery("测试查询");
        query.setTopK(10);
        query.setFusionTopK(30);
        query.setScoreThreshold(0.3);

        HybridRetrievalState state = new HybridRetrievalState(query);

        assertNotNull(state.toMap());
        assertEquals("测试查询", ((RagQuery) state.toMap().get("query")).getQuery());
        assertTrue(state.toMap().containsKey("vectorChunks"));
        assertTrue(state.toMap().containsKey("fullTextChunks"));
        assertTrue(state.toMap().containsKey("fuzzyChunks"));
        assertTrue(state.toMap().containsKey("fused"));
        assertTrue(state.toMap().containsKey("filtered"));
    }

    @Test
    void resultKeysReadableFromMap() {
        HybridRetrievalState state = new HybridRetrievalState(new RagQuery());
        List<RagResult.ChunkResult> chunks = Collections.singletonList(new RagResult.ChunkResult());
        state.toMap().put("filtered", chunks);
        assertEquals(chunks, state.value("filtered").orElse(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd company-rag-rag && mvn -q -o -Dtest=HybridRetrievalStateTest test`
Expected: FAIL — `cannot find symbol: class HybridRetrievalState`

- [ ] **Step 3: Write minimal implementation**

Create `company-rag-rag/src/main/java/com/company/rag/rag/workflow/HybridRetrievalState.java`:

```java
package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 混合检索工作流的状态载体。
 *
 * 包含用户查询参数以及各检索节点产出的中间结果，最终筛选结果由 {"filtered"} 键承载。
 */
public class HybridRetrievalState extends OverAllState {

    /** 用户查询参数。 */
    public static final String KEY_QUERY = "query";
    /** 向量检索结果。 */
    public static final String KEY_VECTOR = "vectorChunks";
    /** 全文检索结果。 */
    public static final String KEY_FULLTEXT = "fullTextChunks";
    /** 模糊检索结果。 */
    public static final String KEY_FUZZY = "fuzzyChunks";
    /** 归一化融合结果。 */
    public static final String KEY_FUSED = "fused";
    /** 最终筛选结果（工作流输出）。 */
    public static final String KEY_FILTERED = "filtered";

    public HybridRetrievalState(RagQuery query) {
        super(Collections.emptyMap());
        Map<String, Object> init = new java.util.HashMap<>();
        init.put(KEY_QUERY, query);
        init.put(KEY_VECTOR, Collections.<RagResult.ChunkResult>emptyList());
        init.put(KEY_FULLTEXT, Collections.<RagResult.ChunkResult>emptyList());
        init.put(KEY_FUZZY, Collections.<RagResult.ChunkResult>emptyList());
        init.put(KEY_FUSED, Collections.emptyList());
        init.put(KEY_FILTERED, Collections.<RagResult.ChunkResult>emptyList());
        this.data.putAll(init);
    }

    /** 按键读取状态值。 */
    @SuppressWarnings("unchecked")
    public <T> java.util.Optional<T> value(String key) {
        return java.util.Optional.ofNullable((T) this.data.get(key));
    }

    /** 输出为可变 Map，供图引擎写入节点结果。 */
    @Override
    public Map<String, Object> toMap() {
        return this.data;
    }
}
```

注意：`OverAllState` 内部 `data` 为 `Map<String,Object>`；若 `data` 字段为 private，请改用在构造函数中先 `super(map)` 传入初始 map。具体适配以下面的「编译期修正」为准：若 `OverAllState` 暴露 `getData()`，则 `toMap()` 返回 `getData()`。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd company-rag-rag && mvn -q -o -Dtest=HybridRetrievalStateTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd "D:/tmp/CompanyRag"
git add company-rag-rag/src/main/java/com/company/rag/rag/workflow/HybridRetrievalState.java company-rag-rag/src/test/java/com/company/rag/rag/workflow/HybridRetrievalStateTest.java
git commit -m "feat(rag): 新增混合检索工作流状态载体"
```

---

### Task 2: 三个检索节点（Vector / FullText / Fuzzy）

Each retrieval node runs a single retriever and writes its chunk list into state, guarding failures per the existing tolerant semantics.

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/VectorRetrieveNode.java`
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FullTextRetrieveNode.java`
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FuzzyRetrieveNode.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/workflow/RetrieveNodeTest.java`
- Depends on: Task 1

- [ ] **Step 1: Write the failing test**

Create `company-rag-rag/src/test/java/com/company/rag/rag/workflow/RetrieveNodeTest.java`:

```java
package com.company.rag.rag.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.retriever.impl.FullTextRetriever;
import com.company.rag.rag.retriever.impl.FuzzyRetriever;
import com.company.rag.rag.retriever.impl.VectorRetriever;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class RetrieveNodeTest {

    @Test
    void vectorNodeWritesChunks() throws Exception {
        RagQuery query = new RagQuery();
        query.setQuery("q");
        VectorRetriever retriever = mock(VectorRetriever.class);
        RagResult.ChunkResult chunk = new RagResult.ChunkResult();
        when(retriever.retrieve(anyString(), anyInt()))
                .thenReturn(Collections.singletonList(chunk));

        VectorRetrieveNode node = new VectorRetrieveNode(retriever);
        Map<String, Object> out = node.apply(new HybridRetrievalState(query)).get();

        List<?> chunks = (List<?>) out.get(HybridRetrievalState.KEY_VECTOR);
        assertEquals(1, chunks.size());
    }

    @Test
    void fullTextNodeWritesChunks() throws Exception {
        FullTextRetriever retriever = mock(FullTextRetriever.class);
        when(retriever.retrieve(anyString(), anyInt()))
                .thenReturn(Collections.singletonList(new RagResult.ChunkResult()));
        FullTextRetrieveNode node = new FullTextRetrieveNode(retriever);
        Map<String, Object> out = node.apply(new HybridRetrievalState(new RagQuery())).get();
        assertEquals(1, ((List<?>) out.get(HybridRetrievalState.KEY_FULLTEXT)).size());
    }

    @Test
    void fuzzyNodeWritesChunks() throws Exception {
        FuzzyRetriever retriever = mock(FuzzyRetriever.class);
        when(retriever.retrieve(anyString(), anyInt()))
                .thenReturn(Collections.singletonList(new RagResult.ChunkResult()));
        FuzzyRetrieveNode node = new FuzzyRetrieveNode(retriever);
        Map<String, Object> out = node.apply(new HybridRetrievalState(new RagQuery())).get();
        assertEquals(1, ((List<?>) out.get(HybridRetrievalState.KEY_FUZZY)).size());
    }

    @Test
    void nodeFailureYieldsEmptyList() throws Exception {
        RagQuery query = new RagQuery();
        query.setQuery("q");
        VectorRetriever retriever = mock(VectorRetriever.class);
        when(retriever.retrieve(anyString(), anyInt()))
                .thenThrow(new RuntimeException("vector down"));

        VectorRetrieveNode node = new VectorRetrieveNode(retriever);
        Map<String, Object> out = node.apply(new HybridRetrievalState(query)).get();
        assertEquals(0, ((List<?>) out.get(HybridRetrievalState.KEY_VECTOR)).size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd company-rag-rag && mvn -q -o -Dtest=RetrieveNodeTest test`
Expected: FAIL — `cannot find symbol: class VectorRetrieveNode`

- [ ] **Step 3: Write minimal implementation**

Create `VectorRetrieveNode.java`:

```java
package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.retriever.impl.VectorRetriever;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/** 向量检索节点：执行单路检索并写入 state。 */
@Slf4j
public class VectorRetrieveNode implements AsyncNodeAction {

    private final VectorRetriever retriever;

    public VectorRetrieveNode(VectorRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> out = new HashMap<>();
            try {
                RagQuery query = state.value(HybridRetrievalState.KEY_QUERY).orElse(null);
                List<RagResult.ChunkResult> chunks = retriever.retrieve(
                        query != null ? query.getQuery() : "",
                        query != null && query.getTopK() != null ? query.getTopK() : 10);
                out.put(HybridRetrievalState.KEY_VECTOR, chunks);
            } catch (Exception e) {
                log.warn("向量检索节点失败，降级为空结果 | error={}", e.getMessage());
                out.put(HybridRetrievalState.KEY_VECTOR, Collections.<RagResult.ChunkResult>emptyList());
            }
            return out;
        });
    }
}
```

Create `FullTextRetrieveNode.java`（结构相同，替换为 `FullTextRetriever` 与 `KEY_FULLTEXT`）：

```java
package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.retriever.impl.FullTextRetriever;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/** 全文检索节点：执行单路检索并写入 state。 */
@Slf4j
public class FullTextRetrieveNode implements AsyncNodeAction {

    private final FullTextRetriever retriever;

    public FullTextRetrieveNode(FullTextRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> out = new HashMap<>();
            try {
                RagQuery query = state.value(HybridRetrievalState.KEY_QUERY).orElse(null);
                List<RagResult.ChunkResult> chunks = retriever.retrieve(
                        query != null ? query.getQuery() : "",
                        query != null && query.getTopK() != null ? query.getTopK() : 10);
                out.put(HybridRetrievalState.KEY_FULLTEXT, chunks);
            } catch (Exception e) {
                log.warn("全文检索节点失败，降级为空结果 | error={}", e.getMessage());
                out.put(HybridRetrievalState.KEY_FULLTEXT, Collections.<RagResult.ChunkResult>emptyList());
            }
            return out;
        });
    }
}
```

Create `FuzzyRetrieveNode.java`（结构相同，替换为 `FuzzyRetriever` 与 `KEY_FUZZY`）：

```java
package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.retriever.impl.FuzzyRetriever;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/** 模糊检索节点：执行单路检索并写入 state。 */
@Slf4j
public class FuzzyRetrieveNode implements AsyncNodeAction {

    private final FuzzyRetriever retriever;

    public FuzzyRetrieveNode(FuzzyRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> out = new HashMap<>();
            try {
                RagQuery query = state.value(HybridRetrievalState.KEY_QUERY).orElse(null);
                List<RagResult.ChunkResult> chunks = retriever.retrieve(
                        query != null ? query.getQuery() : "",
                        query != null && query.getTopK() != null ? query.getTopK() : 10);
                out.put(HybridRetrievalState.KEY_FUZZY, chunks);
            } catch (Exception e) {
                log.warn("模糊检索节点失败，降级为空结果 | error={}", e.getMessage());
                out.put(HybridRetrievalState.KEY_FUZZY, Collections.<RagResult.ChunkResult>emptyList());
            }
            return out;
        });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd company-rag-rag && mvn -q -o -Dtest=RetrieveNodeTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd "D:/tmp/CompanyRag"
git add company-rag-rag/src/main/java/com/company/rag/rag/workflow/ company-rag-rag/src/test/java/com/company/rag/rag/workflow/RetrieveNodeTest.java
git commit -m "feat(rag): 新增向量/全文/模糊三路检索节点"
```

---

### Task 3: 归一化融合节点 `NormalizeFuseNode`

Runs normalization and fusion together into one node, preserving the existing fuse semantics.

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/NormalizeFuseNode.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/workflow/NormalizeFuseNodeTest.java`
- Depends on: Task 1

- [ ] **Step 1: Write the failing test**

Create `company-rag-rag/src/test/java/com/company/rag/rag/workflow/NormalizeFuseNodeTest.java`:

```java
package com.company.rag.rag.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.rag.rag.fusion.RankNormalizer;
import com.company.rag.rag.fusion.ResultFuser;
import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.NormalizedResult;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NormalizeFuseNodeTest {

    @Test
    void writesFusedResult() throws Exception {
        RagQuery query = new RagQuery();
        query.setQuery("q");
        HybridRetrievalState state = new HybridRetrievalState(query);
        state.toMap().put(HybridRetrievalState.KEY_VECTOR,
                Collections.singletonList(new RagResult.ChunkResult()));
        state.toMap().put(HybridRetrievalState.KEY_FULLTEXT, Collections.emptyList());
        state.toMap().put(HybridRetrievalState.KEY_FUZZY, Collections.emptyList());

        RankNormalizer normalizer = mock(RankNormalizer.class);
        when(normalizer.normalize(anyList()))
                .thenReturn(Collections.singletonList(new NormalizedResult()));
        ResultFuser fuser = mock(ResultFuser.class);
        when(fuser.fuse(anyList(), anyList(), anyList(), anyString()))
                .thenReturn(Collections.singletonList(new FusedResult()));

        NormalizeFuseNode node = new NormalizeFuseNode(normalizer, fuser);
        Map<String, Object> out = node.apply(state).get();

        assertEquals(1, ((List<?>) out.get(HybridRetrievalState.KEY_FUSED)).size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd company-rag-rag && mvn -q -o -Dtest=NormalizeFuseNodeTest test`
Expected: FAIL — `cannot find symbol: class NormalizeFuseNode`

- [ ] **Step 3: Write minimal implementation**

Create `company-rag-rag/src/main/java/com/company/rag/rag/workflow/NormalizeFuseNode.java`:

```java
package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.company.rag.rag.fusion.RankNormalizer;
import com.company.rag.rag.fusion.ResultFuser;
import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.NormalizedResult;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/** 归一化 + 融合节点：合并三路结果并按动态权重排序。 */
@Slf4j
public class NormalizeFuseNode implements AsyncNodeAction {

    private final RankNormalizer normalizer;
    private final ResultFuser fuser;

    public NormalizeFuseNode(RankNormalizer normalizer, ResultFuser fuser) {
        this.normalizer = normalizer;
        this.fuser = fuser;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> out = new HashMap<>();
            RagQuery query = state.value(HybridRetrievalState.KEY_QUERY).orElse(null);
            List<RagResult.ChunkResult> vector =
                    state.value(HybridRetrievalState.KEY_VECTOR).orElse(Collections.emptyList());
            List<RagResult.ChunkResult> fullText =
                    state.value(HybridRetrievalState.KEY_FULLTEXT).orElse(Collections.emptyList());
            List<RagResult.ChunkResult> fuzzy =
                    state.value(HybridRetrievalState.KEY_FUZZY).orElse(Collections.emptyList());

            List<NormalizedResult> normVector = normalizer.normalize(vector);
            List<NormalizedResult> normFullText = normalizer.normalize(fullText);
            List<NormalizedResult> normFuzzy = normalizer.normalize(fuzzy);

            String queryText = query != null ? query.getQuery() : "";
            List<FusedResult> fused = fuser.fuse(normVector, normFullText, normFuzzy, queryText);
            out.put(HybridRetrievalState.KEY_FUSED, fused);
            return out;
        });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd company-rag-rag && mvn -q -o -Dtest=NormalizeFuseNodeTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd "D:/tmp/CompanyRag"
git add company-rag-rag/src/main/java/com/company/rag/rag/workflow/NormalizeFuseNode.java company-rag-rag/src/test/java/com/company/rag/rag/workflow/NormalizeFuseNodeTest.java
git commit -m "feat(rag): 新增归一化融合节点"
```

---

### Task 4: 最终筛选节点 `FilterNode`

Performs the final filter (per-doc cap + topK) and writes the workflow output into state.

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FilterNode.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/workflow/FilterNodeTest.java`
- Depends on: Task 1, 3

- [ ] **Step 1: Write the failing test**

Create `company-rag-rag/src/test/java/com/company/rag/rag/workflow/FilterNodeTest.java`:

```java
package com.company.rag.rag.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterNodeTest {

    @Test
    void writesFilteredOutput() throws Exception {
        RagQuery query = new RagQuery();
        query.setTopK(10);
        query.setMaxPerDoc(3);
        HybridRetrievalState state = new HybridRetrievalState(query);
        state.toMap().put(HybridRetrievalState.KEY_FUSED,
                Collections.singletonList(new FusedResult()));

        ResultFilter filter = mock(ResultFilter.class);
        when(filter.finalFilter(anyList(), anyInt(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0));

        FilterNode node = new FilterNode(filter);
        Map<String, Object> out = node.apply(state).get();

        assertEquals(1, ((List<?>) out.get(HybridRetrievalState.KEY_FILTERED)).size());
    }

    @Test
    void emptyFusedYieldsEmptyOutput() throws Exception {
        HybridRetrievalState state = new HybridRetrievalState(new RagQuery());
        ResultFilter filter = mock(ResultFilter.class);
        when(filter.finalFilter(anyList(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        FilterNode node = new FilterNode(filter);
        Map<String, Object> out = node.apply(state).get();
        assertEquals(0, ((List<?>) out.get(HybridRetrievalState.KEY_FILTERED)).size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd company-rag-rag && mvn -q -o -Dtest=FilterNodeTest test`
Expected: FAIL — `cannot find symbol: class FilterNode`

- [ ] **Step 3: Write minimal implementation**

Create `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FilterNode.java`:

```java
package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/** 最终筛选节点：按文档分组上限与 topK 产出一致的结果列表。 */
@Slf4j
public class FilterNode implements AsyncNodeAction {

    private final ResultFilter filter;

    public FilterNode(ResultFilter filter) {
        this.filter = filter;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> out = new HashMap<>();
            RagQuery query = state.value(HybridRetrievalState.KEY_QUERY).orElse(null);
            List<FusedResult> fused =
                    state.value(HybridRetrievalState.KEY_FUSED).orElse(Collections.emptyList());
            int topK = query != null && query.getTopK() != null ? query.getTopK() : 10;
            int maxPerDoc = query != null && query.getMaxPerDoc() != null ? query.getMaxPerDoc() : 3;
            List<RagResult.ChunkResult> filtered = filter.finalFilter(new java.util.ArrayList<>(fused), topK, maxPerDoc);
            out.put(HybridRetrievalState.KEY_FILTERED, filtered);
            return out;
        });
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd company-rag-rag && mvn -q -o -Dtest=FilterNodeTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd "D:/tmp/CompanyRag"
git add company-rag-rag/src/main/java/com/company/rag/rag/workflow/FilterNode.java company-rag-rag/src/test/java/com/company/rag/rag/workflow/FilterNodeTest.java
git commit -m "feat(rag): 新增最终筛选节点"
```

---

### Task 5: 工作流组装与执行 `HybridRetrievalWorkflow`

Assembles the nodes into a `StateGraph` (3 parallel retrieval nodes → normalizeFuse → filter) and exposes `execute(RagQuery)` returning `List<RagResult.ChunkResult>`.

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/HybridRetrievalWorkflow.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/workflow/HybridRetrievalWorkflowTest.java`
- Depends on: Task 1, 2, 3, 4

- [ ] **Step 1: Write the failing test**

Create `company-rag-rag/src/test/java/com/company/rag/rag/workflow/HybridRetrievalWorkflowTest.java`:

```java
package com.company.rag.rag.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.rag.rag.fusion.RankNormalizer;
import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.fusion.ResultFuser;
import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.NormalizedResult;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.retriever.impl.FullTextRetriever;
import com.company.rag.rag.retriever.impl.FuzzyRetriever;
import com.company.rag.rag.retriever.impl.VectorRetriever;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HybridRetrievalWorkflowTest {

    private VectorRetriever vectorRetriever;
    private FullTextRetriever fullTextRetriever;
    private FuzzyRetriever fuzzyRetriever;
    private RankNormalizer normalizer;
    private ResultFuser fuser;
    private ResultFilter filter;
    private HybridRetrievalWorkflow workflow;

    @BeforeEach
    void setUp() throws Exception {
        vectorRetriever = mock(VectorRetriever.class);
        fullTextRetriever = mock(FullTextRetriever.class);
        fuzzyRetriever = mock(FuzzyRetriever.class);
        normalizer = mock(RankNormalizer.class);
        fuser = mock(ResultFuser.class);
        filter = mock(ResultFilter.class);

        when(vectorRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(Collections.singletonList(new RagResult.ChunkResult()));
        when(fullTextRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(fuzzyRetriever.retrieve(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(normalizer.normalize(anyList()))
                .thenReturn(Collections.singletonList(new NormalizedResult()));
        when(fuser.fuse(anyList(), anyList(), anyList(), anyString()))
                .thenReturn(Collections.singletonList(new FusedResult()));
        when(filter.finalFilter(anyList(), anyInt(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0));

        workflow = new HybridRetrievalWorkflow(
                vectorRetriever, fullTextRetriever, fuzzyRetriever,
                normalizer, fuser, filter);
    }

    @Test
    void executesAndReturnsFilteredChunks() {
        RagQuery query = new RagQuery();
        query.setQuery("测试");
        List<RagResult.ChunkResult> result = workflow.execute(query);
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void singleRetrievalFailureStillSucceeds() {
        when(vectorRetriever.retrieve(anyString(), anyInt()))
                .thenThrow(new RuntimeException("vector down"));
        RagQuery query = new RagQuery();
        query.setQuery("测试");
        List<RagResult.ChunkResult> result = workflow.execute(query);
        assertNotNull(result);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd company-rag-rag && mvn -q -o -Dtest=HybridRetrievalWorkflowTest test`
Expected: FAIL — `cannot find symbol: class HybridRetrievalWorkflow`

- [ ] **Step 3: Write minimal implementation**

Create `company-rag-rag/src/main/java/com/company/rag/rag/workflow/HybridRetrievalWorkflow.java`:

```java
package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.company.rag.rag.fusion.RankNormalizer;
import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.fusion.ResultFuser;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.retriever.impl.FullTextRetriever;
import com.company.rag.rag.retriever.impl.FuzzyRetriever;
import com.company.rag.rag.retriever.impl.VectorRetriever;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * 混合检索工作流。
 *
 * 将三路检索（向量/全文/模糊）作为并行节点、归一化融合与最终筛选作为串行节点组装成 StateGraph。
 * 对外行为与原 MultiRetrieveServiceImpl 保持一致，仅替换编排方式。
 */
@Slf4j
public class HybridRetrievalWorkflow {

    private final CompiledGraph graph;

    public HybridRetrievalWorkflow(
            VectorRetriever vectorRetriever,
            FullTextRetriever fullTextRetriever,
            FuzzyRetriever fuzzyRetriever,
            RankNormalizer normalizer,
            ResultFuser fuser,
            ResultFilter filter) {
        try {
            StateGraph stateGraph = new StateGraph();
            stateGraph.addNode("vectorRetrieval", new VectorRetrieveNode(vectorRetriever));
            stateGraph.addNode("fullTextRetrieval", new FullTextRetrieveNode(fullTextRetriever));
            stateGraph.addNode("fuzzyRetrieval", new FuzzyRetrieveNode(fuzzyRetriever));
            stateGraph.addNode("normalizeAndFuse", new NormalizeFuseNode(normalizer, fuser));
            stateGraph.addNode("finalFilter", new FilterNode(filter));

            // 三路检索并行（无相互依赖），随后串行融合与筛选
            stateGraph.addEdge(StateGraph.START, "vectorRetrieval");
            stateGraph.addEdge(StateGraph.START, "fullTextRetrieval");
            stateGraph.addEdge(StateGraph.START, "fuzzyRetrieval");
            stateGraph.addEdge("vectorRetrieval", "normalizeAndFuse");
            stateGraph.addEdge("fullTextRetrieval", "normalizeAndFuse");
            stateGraph.addEdge("fuzzyRetrieval", "normalizeAndFuse");
            stateGraph.addEdge("normalizeAndFuse", "finalFilter");
            stateGraph.addEdge("finalFilter", StateGraph.END);

            this.graph = stateGraph.compile();
        } catch (Exception e) {
            throw new IllegalStateException("初始化混合检索工作流失败", e);
        }
    }

    /**
     * 执行混合检索工作流。
     *
     * @param query 用户查询
     * @return 最终筛选后的段落列表（与旧 MultiRetrieveServiceImpl 行为一致）
     */
    @SuppressWarnings("unchecked")
    public List<RagResult.ChunkResult> execute(RagQuery query) {
        HybridRetrievalState state = new HybridRetrievalState(query);
        Optional<OverAllState> finalState = graph.invoke(state, null);
        if (finalState.isPresent()) {
            Optional<List<RagResult.ChunkResult>> filtered =
                    finalState.get().value(HybridRetrievalState.KEY_FILTERED);
            if (filtered.isPresent()) {
                return filtered.get();
            }
        }
        return java.util.Collections.emptyList();
    }
}
```

> 编译期注意事项：若 `CompiledGraph.invoke` 的 `RunnableConfig` 不允许传 null，请改为无参变体 `graph.invoke(state.toMap())`，或 `graph.invoke(Map)` 形式。测试通过为准。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd company-rag-rag && mvn -q -o -Dtest=HybridRetrievalWorkflowTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd "D:/tmp/CompanyRag"
git add company-rag-rag/src/main/java/com/company/rag/rag/workflow/HybridRetrievalWorkflow.java company-rag-rag/src/test/java/com/company/rag/rag/workflow/HybridRetrievalWorkflowTest.java
git commit -m "feat(rag): 新增混合检索 StateGraph 工作流组装与执行"
```

---

### Task 6: 接入 `MultiRetrieveServiceImpl`

Delegates the existing `retrieve()` to the new workflow, keeping the interface and upstream behavior unchanged.

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/service/impl/MultiRetrieveServiceImpl.java`
- Depends on: Task 5

- [ ] **Step 1: Rewrite `MultiRetrieveServiceImpl` to delegate to workflow**

Replace the class body. The three retrievers plus normalizer/fuser/filter are now injected into the `HybridRetrievalWorkflow`; `retrieve()` delegates:

```java
package com.company.rag.rag.service.impl;

import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.retriever.impl.FullTextRetriever;
import com.company.rag.rag.retriever.impl.FuzzyRetriever;
import com.company.rag.rag.retriever.impl.VectorRetriever;
import com.company.rag.rag.fusion.RankNormalizer;
import com.company.rag.rag.fusion.ResultFuser;
import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.service.MultiRetrieveService;
import com.company.rag.rag.workflow.HybridRetrievalWorkflow;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 多路检索服务实现。
 *
 * 自 2026-09-13 起改用 StateGraph 工作流编排（见 HybridRetrievalWorkflow），
 * 对外行为与旧流水线保持一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiRetrieveServiceImpl implements MultiRetrieveService {

    private final HybridRetrievalWorkflow workflow;

    @Override
    public List<RagResult.ChunkResult> retrieve(RagQuery query) {
        log.info("开始多路混合检索（StateGraph 工作流）| query={} | strategy={}",
                query.getQuery(), query.getRetrievalStrategy());
        return workflow.execute(query);
    }
}
```

- [ ] **Step 2: Resolve bean wiring**

`HybridRetrievalWorkflow` takes `(VectorRetriever, FullTextRetriever, FuzzyRetriever, RankNormalizer, ResultFuser, ResultFilter)` in that order. Add a `@Bean`/`@Component` or `@Configuration` factory for it if Spring cannot construct it automatically (record the decision in the plan as implemented). Given it has a single constructor with all-`@Component` args, Spring will auto-wire it; the `@Component`-annotated retrievers/fusers already exist as `@Component`/`@Service`.

为明确这些检索/融合组件确为 Spring Bean，请核验：`VectorRetriever`、`FullTextRetriever`、`FuzzyRetriever` 是否带 `@Component`/`@Service`；若不带，则在计划落地时为其补充相应注解（它们是既有 class，此项以实际编译通过为准）。

- [ ] **Step 3: Compile the module**

Run: `cd company-rag-rag && mvn -q -o -DskipTests compile`
Expected: BUILD SUCCESS（若离线仓库有缺件则改 `mvn -q compile`）

- [ ] **Step 4: Run scoped tests to confirm no regression**

由于 `MultiRetrieveServiceImpl` 无独立既有单测（旧集成测试已迁至 bootstrap 真库 IT，默认跳过），运行本模块全部单测类确认无回归：

Run: `cd company-rag-rag && mvn -q -o test`
Expected: BUILD SUCCESS，本模块已有测试全部通过

- [ ] **Step 5: Commit**

```bash
cd "D:/tmp/CompanyRag"
git add company-rag-rag/src/main/java/com/company/rag/rag/service/impl/MultiRetrieveServiceImpl.java
git commit -m "refactor(rag): MultiRetrieveServiceImpl 改由 StateGraph 工作流编排"
```

---

## Self-Review

**1. Spec coverage**
- §3.1 所有组件（Workflow / State / 5 节点）→ Task 1–5 ✓
- §3.3 策略分派保持不变 → Task 6 不改 `RagSearchServiceImpl.hybridRetrieve` ✓
- §4 数据流（invoke → 读 `filtered` 键返回）→ Task 5 ✓
- §5 容错语义（单路失败写空列表）→ Task 2 `nodeFailureYieldsEmptyList` + Task 5 `singleRetrievalFailureStillSucceeds` ✓；融合/筛选异常冒泡 → `MultiRetrieveServiceImpl` 保留外层语义（现状兼容）✓
- §6 测试策略 → Task 1–5 单测 + 回归说明 ✓

**2. Placeholder scan**
- 无 TBD/TODO/“待实现”。Task 6 Step 2 中“以实际编译通过为准”为落地核验说明，非占位符。

**3. Type consistency**
- 图中使用的 `KEY_QUERY/KEY_VECTOR/KEY_FULLTEXT/KEY_FUZZY/KEY_FUSED/KEY_FILTERED` 常量在 Task 1 定义并被 Task 2–5 一致引用 ✓
- `execute(RagQuery)` 返回 `List<RagResult.ChunkResult>` 在 Task 5/6 一致 ✓
- 名字一致：`VectorRetrieveNode` / `FullTextRetrieveNode` / `FuzzyRetrieveNode` / `NormalizeFuseNode` / `FilterNode` / `HybridRetrievalWorkflow` / `HybridRetrievalState` 全程一致 ✓

> 若实现中 `OverAllState.data` 不可直接访问或构造方式存在差异，请按编译错误就近调整，保持行为与状态键名不变。
