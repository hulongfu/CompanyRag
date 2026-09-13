# 混合检索 StateGraph 工作流实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变对外行为的前提下，把 `MultiRetrieveServiceImpl` 内部的确定性混合检索流水线重构成一张 Spring AI Alibaba `StateGraph` 工作流，用于体验图编排效果。

**Architecture:** 保持 `MultiRetrieveService` 接口与 `RagSearchServiceImpl` 上层调用不变，仅将 `MultiRetrieveServiceImpl.retrieve()` 委派给新增的 `HybridRetrievalWorkflow`。工作流由三路并行检索节点（向量/全文/模糊）+ 归一化融合节点 + 最终筛选节点组成，业务逻辑复用现有 `VectorRetriever`、`FullTextRetriever`、`FuzzyRetriever`、`RankNormalizer`、`ResultFuser`、`ResultFilter`。工作流直接使用图引擎的 `OverAllState`（final 类，不可继承），不自定义状态类。

**Tech Stack:** Java 17、Spring Boot 3.4、Spring AI 1.1 / Spring AI Alibaba Graph Core 1.1.2.0（`StateGraph` / `CompiledGraph` / `OverAllState` / `AsyncNodeAction`）、Reactor（`stream()` 返回 `Flux`）、JUnit 5 + Mockito。

---

## 文件结构

新增（均在 `company-rag-rag` 模块，`workflow` 子包）：

- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/VectorRetrieveNode.java` — 向量检索节点。
- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FullTextRetrieveNode.java` — 全文检索节点。
- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FuzzyRetrieveNode.java` — 模糊检索节点。
- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/NormalizeFuseNode.java` — 归一化 + 融合节点。
- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FilterNode.java` — 最终筛选节点。
- `company-rag-rag/src/main/java/com/company/rag/rag/workflow/HybridRetrievalWorkflow.java` — 组装并执行图工作流，暴露 `execute(RagQuery)`。

修改：

- `company-rag-rag/src/main/java/com/company/rag/rag/service/impl/MultiRetrieveServiceImpl.java` — 内部委派给 `HybridRetrievalWorkflow`。

测试（均为单元测试，JUnit 5 + Mockito，不依赖 PG/Redis/外网）：

- `company-rag-rag/src/test/java/com/company/rag/rag/workflow/RetrieveNodeTest.java`（vector / fulltext / fuzzy 三个节点共用）
- `company-rag-rag/src/test/java/com/company/rag/rag/workflow/NormalizeFuseNodeTest.java`
- `company-rag-rag/src/test/java/com/company/rag/rag/workflow/FilterNodeTest.java`
- `company-rag-rag/src/test/java/com/company/rag/rag/workflow/HybridRetrievalWorkflowTest.java`

不动：`MultiRetrieveService` 接口、`RagSearchServiceImpl`、Controller、DTO、数据层、`company-rag-bootstrap`。

**命令前缀：** 本模块构建/测试用 `cd company-rag-rag && mvn -q -o test`（离线）或 `mvn -q test`；单测类用 `mvn -q -o -Dtest=<ClassName> test`。验证范围始终限制在本模块相关测试类，不跑全仓库。

---

## 关键既有组件签名（供各任务引用）

- `VectorRetriever.retrieve(String query, int topK)` → `List<RagResult.ChunkResult>`
- `FullTextRetriever.retrieve(String query, int topK)` → `List<RagResult.ChunkResult>`
- `FuzzyRetriever.retrieve(String query, int topK)` → `List<RagResult.ChunkResult>`
- `RankNormalizer.normalize(List<RagResult.ChunkResult>)` → `List<NormalizedResult>`
- `ResultFuser.fuse(List<NormalizedResult> vector, List<NormalizedResult> fulltext, List<NormalizedResult> fuzzy, String query)` → `List<FusedResult>`
- `ResultFilter.filter(List<FusedResult>, int fusionTopK, Double scoreThreshold)` → `List<FusedResult>`；`ResultFilter.finalFilter(List<RagResult.ChunkResult>, int topK, int maxPerDoc)` → `List<RagResult.ChunkResult>`

图 API（已用 javap 核实 `spring-ai-alibaba-graph-core` 1.1.2.0 jar）：
- `StateGraph`（无参构造可用）、`addNode(String, AsyncNodeAction)` / `addEdge(String, String)` / `compile()` → `CompiledGraph`；常量 `StateGraph.START` / `StateGraph.END`。
- `OverAllState` 是 **final 类，不可继承**。用 `new OverAllState(Map<String,Object>)` 创建；`<T> Optional<T> value(String)` 读值（final 方法）；节点返回 `Map<String,Object>` 由引擎写回。
- 节点动作类型：`AsyncNodeAction extends Function<OverAllState, CompletableFuture<Map<String,Object>>>`，需实现 `apply(OverAllState)`。也可用 `AsyncNodeAction.node_async(NodeAction)` 包装同步 `NodeAction`。

**状态键（在工作流/节点间以常量共享，定义在 `HybridRetrievalWorkflow`）：**
- `query` → `RagQuery`
- `vectorChunks` → `List<RagResult.ChunkResult>`
- `fullTextChunks` → `List<RagResult.ChunkResult>`
- `fuzzyChunks` → `List<RagResult.ChunkResult>`
- `fused` → `List<FusedResult>`
- `filtered` → `List<RagResult.ChunkResult>`（工作流输出）

---

### Task 1: 三个检索节点（Vector / FullText / Fuzzy）

Each retrieval node runs a single retriever and writes its chunk list into state under the shared keys, guarding failures per the existing tolerant semantics (a failed single retrieval writes an empty list instead of throwing).

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/VectorRetrieveNode.java`
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FullTextRetrieveNode.java`
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FuzzyRetrieveNode.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/workflow/RetrieveNodeTest.java`
- Depends on: 无

- [ ] **Step 1: Write the failing test**

Create `company-rag-rag/src/test/java/com/company/rag/rag/workflow/RetrieveNodeTest.java`. 测试通过 `OverAllState` 构造含 `KEY_QUERY` 的初始 map，直接调用节点 `apply(new OverAllState(init))` 并断言返回 map 中对应键的值长度；失败分支断言空列表：

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrieveNodeTest {

    private static OverAllState stateWith(RagQuery query) {
        Map<String, Object> m = new HashMap<>();
        m.put(HybridRetrievalWorkflow.KEY_QUERY, query);
        m.put(HybridRetrievalWorkflow.KEY_VECTOR, Collections.emptyList());
        m.put(HybridRetrievalWorkflow.KEY_FULLTEXT, Collections.emptyList());
        m.put(HybridRetrievalWorkflow.KEY_FUZZY, Collections.emptyList());
        return new OverAllState(m);
    }

    @Test
    void vectorNodeWritesChunks() throws Exception {
        RagQuery query = new RagQuery();
        query.setQuery("q");
        VectorRetriever retriever = mock(VectorRetriever.class);
        when(retriever.retrieve(anyString(), anyInt()))
                .thenReturn(Collections.singletonList(new RagResult.ChunkResult()));

        VectorRetrieveNode node = new VectorRetrieveNode(retriever);
        Map<String, Object> out = node.apply(stateWith(query)).get();

        assertEquals(1, ((List<?>) out.get(HybridRetrievalWorkflow.KEY_VECTOR)).size());
    }

    @Test
    void fullTextNodeWritesChunks() throws Exception {
        FullTextRetriever retriever = mock(FullTextRetriever.class);
        when(retriever.retrieve(anyString(), anyInt()))
                .thenReturn(Collections.singletonList(new RagResult.ChunkResult()));
        FullTextRetrieveNode node = new FullTextRetrieveNode(retriever);
        Map<String, Object> out = node.apply(stateWith(new RagQuery())).get();
        assertEquals(1, ((List<?>) out.get(HybridRetrievalWorkflow.KEY_FULLTEXT)).size());
    }

    @Test
    void fuzzyNodeWritesChunks() throws Exception {
        FuzzyRetriever retriever = mock(FuzzyRetriever.class);
        when(retriever.retrieve(anyString(), anyInt()))
                .thenReturn(Collections.singletonList(new RagResult.ChunkResult()));
        FuzzyRetrieveNode node = new FuzzyRetrieveNode(retriever);
        Map<String, Object> out = node.apply(stateWith(new RagQuery())).get();
        assertEquals(1, ((List<?>) out.get(HybridRetrievalWorkflow.KEY_FUZZY)).size());
    }

    @Test
    void nodeFailureYieldsEmptyList() throws Exception {
        RagQuery query = new RagQuery();
        query.setQuery("q");
        VectorRetriever retriever = mock(VectorRetriever.class);
        when(retriever.retrieve(anyString(), anyInt()))
                .thenThrow(new RuntimeException("vector down"));

        VectorRetrieveNode node = new VectorRetrieveNode(retriever);
        Map<String, Object> out = node.apply(stateWith(query)).get();
        assertEquals(0, ((List<?>) out.get(HybridRetrievalWorkflow.KEY_VECTOR)).size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd company-rag-rag && mvn -q -o -Dtest=RetrieveNodeTest test`
Expected: FAIL — `cannot find symbol: class VectorRetrieveNode`（且 `HybridRetrievalWorkflow` 尚不存在）

- [ ] **Step 3: Write minimal implementation**

Create `VectorRetrieveNode.java`. 它实现 `AsyncNodeAction`，在 `apply` 中读取 `state.value(KEY_QUERY)` 得到 `RagQuery`，调用 `VectorRetriever.retrieve(query, topK)`，put 到 `KEY_VECTOR`；异常时写空列表：

```java
package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/** 向量检索节点：执行单路检索并写入 state（失败时降级为空列表）。 */
@Slf4j
public class VectorRetrieveNode implements AsyncNodeAction {

    private final com.company.rag.rag.retriever.impl.VectorRetriever retriever;

    public VectorRetrieveNode(com.company.rag.rag.retriever.impl.VectorRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> out = new HashMap<>();
            try {
                RagQueryAccess query = fetch query from state via HybridRetrievalWorkflow.KEY_QUERY;
                List<?> chunks = retriever.retrieve(query text, topK);
                out.put(HybridRetrievalWorkflow.KEY_VECTOR, chunks);
            } catch (Exception e) {
                log.warn("向量检索节点失败，降级为空结果 | error={}", e.getMessage());
                out.put(HybridRetrievalWorkflow.KEY_VECTOR, Collections.emptyList());
            }
            return out;
        });
    }
}
```

（上面 `RagQueryAccess` / `fetch query...` 为占位示意，实现时必须写出真实可编译代码：用 `com.company.rag.rag.model.RagQuery query = state.<RagQuery>value(HybridRetrievalWorkflow.KEY_QUERY).orElse(null);`，`String text = query != null ? query.getQuery() : "";`，`int topK = query != null && query.getTopK() != null ? query.getTopK() : 10;`。）

- [ ] **Step 4: Run test to verify it passes**

Run: `cd company-rag-rag && mvn -q -o -Dtest=RetrieveNodeTest test`
Expected: PASS（三个正常 + 一个失败分支全部通过）

- [ ] **Step 5: Commit**

```bash
cd "D:/tmp/CompanyRag"
git add company-rag-rag/src/main/java/com/company/rag/rag/workflow/VectorRetrieveNode.java company-rag-rag/src/main/java/com/company/rag/rag/workflow/FullTextRetrieveNode.java company-rag-rag/src/main/java/com/company/rag/rag/workflow/FuzzyRetrieveNode.java company-rag-rag/src/test/java/com/company/rag/rag/workflow/RetrieveNodeTest.java
git commit -m "feat(rag): 新增向量/全文/模糊三路检索节点"
```

注意：为了编译容器最小，这里先不引入 `HybridRetrievalWorkflow` 常量类。更简洁的做法是让三个节点与工作流共用一组**常量**。建议将这些键常量放到位 `HybridRetrievalWorkflow`（后续 Task 5 创建）。本 Task 测试依赖这些常量，因此需在 Task 5 落地常量后回归通过；若希望本 Task 独立通过，可先用局部字符串常量并在 Task 5 统一改为引用。以「本模块 `mvn test` 最终全绿」为验收标准，允许 Task 间适度调整细节。

---

### Task 2: 归一化融合节点 `NormalizeFuseNode`

Runs normalization and fusion together into one node, preserving the existing fuse semantics.

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/NormalizeFuseNode.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/workflow/NormalizeFuseNodeTest.java`
- Depends on: Task 1（概念上，物理上仅依赖 OverAllState 键）

- [ ] **Step 1: Write the failing test**

Create `company-rag-rag/src/test/java/com/company/rag/rag/workflow/NormalizeFuseNodeTest.java`：

```java
package com.company.rag.rag.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.OverAllState;
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
import org.junit.jupiter.api.Test;

class NormalizeFuseNodeTest {

    @Test
    void writesFusedResult() throws Exception {
        RagQuery query = new RagQuery();
        query.setQuery("q");
        Map<String, Object> m = new HashMap<>();
        m.put(HybridRetrievalWorkflow.KEY_QUERY, query);
        m.put(HybridRetrievalWorkflow.KEY_VECTOR,
                Collections.singletonList(new RagResult.ChunkResult()));
        m.put(HybridRetrievalWorkflow.KEY_FULLTEXT, Collections.emptyList());
        m.put(HybridRetrievalWorkflow.KEY_FUZZY, Collections.emptyList());
        OverAllState state = new OverAllState(m);

        RankNormalizer normalizer = mock(RankNormalizer.class);
        when(normalizer.normalize(anyList()))
                .thenReturn(Collections.singletonList(new NormalizedResult()));
        ResultFuser fuser = mock(ResultFuser.class);
        when(fuser.fuse(anyList(), anyList(), anyList(), anyString()))
                .thenReturn(Collections.singletonList(new FusedResult()));

        NormalizeFuseNode node = new NormalizeFuseNode(normalizer, fuser);
        Map<String, Object> out = node.apply(state).get();

        assertEquals(1, ((List<?>) out.get(HybridRetrievalWorkflow.KEY_FUSED)).size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd company-rag-rag && mvn -q -o -Dtest=NormalizeFuseNodeTest test`
Expected: FAIL — `cannot find symbol: class NormalizeFuseNode`

- [ ] **Step 3: Write minimal implementation**

Create `NormalizeFuseNode.java`：实现 `AsyncNodeAction`，从 state 读 `KEY_VECTOR/KEY_FULLTEXT/KEY_FUZZY/KEY_QUERY`，调用 `normalizer.normalize` 三次 + `fuser.fuse`，写 `KEY_FUSED`。用真实可编译代码（读取 `Object` 并安全强转为 `List<RagResult.ChunkResult>`，`Collections.emptyList()` 兜底）：

```java
package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.company.rag.rag.fusion.RankNormalizer;
import com.company.rag.rag.fusion.ResultFuser;
import com.company.rag.rag.model.NormalizedResult;
import com.company.rag.rag.model.RagQuery;
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
            RagQuery query = state.value(HybridRetrievalWorkflow.KEY_QUERY).orElse(null);
            List<NormalizedResult> normVector =
                    normalizer.normalize(chunks(state, HybridRetrievalWorkflow.KEY_VECTOR));
            List<NormalizedResult> normFullText =
                    normalizer.normalize(chunks(state, HybridRetrievalWorkflow.KEY_FULLTEXT));
            List<NormalizedResult> normFuzzy =
                    normalizer.normalize(chunks(state, HybridRetrievalWorkflow.KEY_FUZZY));
            String text = query != null ? query.getQuery() : "";
            out.put(HybridRetrievalWorkflow.KEY_FUSED,
                    fuser.fuse(normVector, normFullText, normFuzzy, text));
            return out;
        });
    }

    @SuppressWarnings("unchecked")
    private List<com.company.rag.rag.model.RagResult.ChunkResult> chunks(
            OverAllState state, String key) {
        return state.<List<com.company.rag.rag.model.RagResult.ChunkResult>>value(key)
                .orElse(Collections.emptyList());
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

### Task 3: 最终筛选节点 `FilterNode`

Performs the final filter (per-doc cap + topK) and writes the workflow output into state under `KEY_FILTERED`.

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/FilterNode.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/workflow/FilterNodeTest.java`
- Depends on: Task 1（概念上）

- [ ] **Step 1: Write the failing test**

Create `company-rag-rag/src/test/java/com/company/rag/rag/workflow/FilterNodeTest.java`：

```java
package com.company.rag.rag.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.RagQuery;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterNodeTest {

    @Test
    void writesFilteredOutput() throws Exception {
        RagQuery query = new RagQuery();
        query.setTopK(10);
        query.setMaxPerDoc(3);
        Map<String, Object> m = new HashMap<>();
        m.put(HybridRetrievalWorkflow.KEY_QUERY, query);
        m.put(HybridRetrievalWorkflow.KEY_FUSED, Collections.singletonList(new FusedResult()));
        OverAllState state = new OverAllState(m);

        ResultFilter filter = mock(ResultFilter.class);
        when(filter.finalFilter(anyList(), anyInt(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0));

        FilterNode node = new FilterNode(filter);
        Map<String, Object> out = node.apply(state).get();
        assertEquals(1, ((java.util.List<?>) out.get(HybridRetrievalWorkflow.KEY_FILTERED)).size());
    }

    @Test
    void emptyFusedYieldsEmptyOutput() throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put(HybridRetrievalWorkflow.KEY_QUERY, new RagQuery());
        m.put(HybridRetrievalWorkflow.KEY_FUSED, Collections.emptyList());
        OverAllState state = new OverAllState(m);

        ResultFilter filter = mock(ResultFilter.class);
        when(filter.finalFilter(anyList(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        FilterNode node = new FilterNode(filter);
        Map<String, Object> out = node.apply(state).get();
        assertEquals(0, ((java.util.List<?>) out.get(HybridRetrievalWorkflow.KEY_FILTERED)).size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd company-rag-rag && mvn -q -o -Dtest=FilterNodeTest test`
Expected: FAIL — `cannot find symbol: class FilterNode`

- [ ] **Step 3: Write minimal implementation**

Create `FilterNode.java`：实现 `AsyncNodeAction`，从 state 读 `KEY_FUSED`（`List<FusedResult>`）与 `KEY_QUERY`（topK/maxPerDoc 默认 10/3），调用 `filter.finalFilter(new ArrayList<>(fused), topK, maxPerDoc)`，写 `KEY_FILTERED`：

```java
package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.RagQuery;
import java.util.ArrayList;
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
            RagQuery query = state.value(HybridRetrievalWorkflow.KEY_QUERY).orElse(null);
            List<FusedResult> fused =
                    state.<List<FusedResult>>value(HybridRetrievalWorkflow.KEY_FUSED)
                            .orElse(Collections.emptyList());
            int topK = query != null && query.getTopK() != null ? query.getTopK() : 10;
            int maxPerDoc = query != null && query.getMaxPerDoc() != null ? query.getMaxPerDoc() : 3;
            out.put(HybridRetrievalWorkflow.KEY_FILTERED,
                    filter.finalFilter(new ArrayList<>(fused), topK, maxPerDoc));
            return out;
        });
    }
}
```

> 注意：若 `ResultFilter.finalFilter` 的实际签名与 `List<RagResult.ChunkResult>` 相关（而非直接接收 `List<FusedResult>`），请以真实定义为准调整入参类型，保持对外行为不变。实现时先阅读 `ResultFilter.java` 源码确认。

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

### Task 4: 工作流组装与执行 `HybridRetrievalWorkflow`

Assembles the nodes into a `StateGraph` (3 parallel retrieval nodes → normalizeFuse → filter), defines the shared state keys, and exposes `execute(RagQuery)` returning `List<RagResult.ChunkResult>`.

**Files:**
- Create: `company-rag-rag/src/main/java/com/company/rag/rag/workflow/HybridRetrievalWorkflow.java`
- Test: `company-rag-rag/src/test/java/com/company/rag/rag/workflow/HybridRetrievalWorkflowTest.java`
- Depends on: Task 1, 2, 3

- [ ] **Step 1: Write the failing test**

Create `company-rag-rag/src/test/java/com/company/rag/rag/workflow/HybridRetrievalWorkflowTest.java`。以真实 Bean 注入构造 `HybridRetrievalWorkflow`，`execute(query)` 后断言返回非空且长度为预期：

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
    void setUp() {
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

Create `HybridRetrievalWorkflow.java`。它定义全部状态键常量、组装 `StateGraph`（START→三检索节点→normalizeAndFuse→finalFilter→END）、`execute` 用 `graph.invoke(initMap)` 执行并读取 `KEY_FILTERED`。**编译要点（已用 javap 核实）：** `CompiledGraph` 仅提供 `invoke(Map, RunnableConfig)`、`invoke(OverAllState, RunnableConfig)`、`invoke(Map)` 三个重载，**没有** `invoke(OverAllState)` 无 config 变体，且 `OverAllState` 是 final 也无 `toMap()`。因此 execute 里直接用 `graph.invoke(initMap)` 传初始 `Map<String,Object>`（图内部会构造 `OverAllState` 并注册各键），返回 `Optional<OverAllState>` 后再 `value(KEY_FILTERED)` 读结果：

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /** 状态键：用户查询参数。 */
    public static final String KEY_QUERY = "query";
    /** 状态键：向量检索结果。 */
    public static final String KEY_VECTOR = "vectorChunks";
    /** 状态键：全文检索结果。 */
    public static final String KEY_FULLTEXT = "fullTextChunks";
    /** 状态键：模糊检索结果。 */
    public static final String KEY_FUZZY = "fuzzyChunks";
    /** 状态键：归一化融合结果。 */
    public static final String KEY_FUSED = "fused";
    /** 状态键：最终筛选结果（工作流输出）。 */
    public static final String KEY_FILTERED = "filtered";

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
        Map<String, Object> init = new HashMap<>();
        init.put(KEY_QUERY, query);
        init.put(KEY_VECTOR, Collections.<RagResult.ChunkResult>emptyList());
        init.put(KEY_FULLTEXT, Collections.<RagResult.ChunkResult>emptyList());
        init.put(KEY_FUZZY, Collections.<RagResult.ChunkResult>emptyList());
        init.put(KEY_FUSED, Collections.emptyList());
        init.put(KEY_FILTERED, Collections.<RagResult.ChunkResult>emptyList());

        Optional<OverAllState> finalState = graph.invoke(init);
        if (finalState.isPresent()) {
            Optional<List<RagResult.ChunkResult>> filtered =
                    finalState.get().<List<RagResult.ChunkResult>>value(KEY_FILTERED);
            if (filtered.isPresent()) {
                return filtered.get();
            }
        }
        return Collections.emptyList();
    }
}
```

> 编译期校验：先读取 `VectorRetrieveNode`、`FullTextRetrieveNode`、`FuzzyRetrieveNode`、`NormalizeFuseNode`、`FilterNode` 确保其类名/构造器签名与本工作流引用的完全一致（尤其 `FilterNode` 构造参数是否为单个 `ResultFilter`）。

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

### Task 5: 接入 `MultiRetrieveServiceImpl`

Delegates the existing `retrieve()` to the new workflow, keeping the interface and upstream behavior unchanged.

**Files:**
- Modify: `company-rag-rag/src/main/java/com/company/rag/rag/service/impl/MultiRetrieveServiceImpl.java`
- Depends on: Task 4

- [ ] **Step 1: Rewrite `MultiRetrieveServiceImpl` to delegate to workflow**

把 `MultiRetrieveServiceImpl` 改为仅注入 `HybridRetrievalWorkflow` 并委派：

```java
package com.company.rag.rag.service.impl;

import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
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

- [ ] **Step 2: Verify bean wiring for `HybridRetrievalWorkflow`**

`HybridRetrievalWorkflow` 构造参数（VectorRetriever/FullTextRetriever/FuzzyRetriever/RankNormalizer/ResultFuser/ResultFilter）均为 Spring 管理的组件。在 `company-rag-rag` 模块范围内确认这些组件已带 `@Component`/`@Service` 类注解，使 Spring 能自动装配。若某组件非 Spring Bean，则为其补充相应注解（既有 class 上新增，不影响其它调用）。以模块编译通过为准。

- [ ] **Step 3: Compile the module**

Run: `cd company-rag-rag && mvn -q -o -DskipTests compile`
Expected: BUILD SUCCESS（若离线仓库缺件则 `mvn -q compile`）

- [ ] **Step 4: Run scoped workflow tests to confirm green**

Run: `cd company-rag-rag && mvn -q -o -Dtest=RetrieveNodeTest,NormalizeFuseNodeTest,FilterNodeTest,HybridRetrievalWorkflowTest test`
Expected: BUILD SUCCESS，4 个测试类全部通过

- [ ] **Step 5: Commit**

```bash
cd "D:/tmp/CompanyRag"
git add company-rag-rag/src/main/java/com/company/rag/rag/service/impl/MultiRetrieveServiceImpl.java
git commit -m "refactor(rag): MultiRetrieveServiceImpl 改由 StateGraph 工作流编排"
```

---

## Self-Review

**1. Spec coverage**
- §3.1 所有组件（5 节点 + Workflow）→ Task 1–4 ✓（State 类因 `OverAllState` 为 final 不可继承，合并进 Workflow 常量 + OverAllState 直接使用，符合 §4 数据流本质）
- §3.3 策略分派保持不变 → Task 5 不改 `RagSearchServiceImpl.hybridRetrieve` ✓
- §4 数据流（OverAllState + invoke → 读 `filtered` 键返回）→ Task 4 ✓
- §5 容错语义（单路失败写空列表）→ Task 1 `nodeFailureYieldsEmptyList` + Task 4 `singleRetrievalFailureStillSucceeds` ✓
- §6 测试策略 → Task 1–4 单测 + Task 5 scoped 回归 ✓

**2. Placeholder scan**
- Task 1 Step 3 含一段「占位示意」并明确要求实现时写出真实可编译代码（非交付占位符，而是交接说明）。计划其余部分无 TBD/TODO。
- 修正：较上一版计划移除不可继承的 `HybridRetrievalState` 类，状态键常量统一定义于 `HybridRetrievalWorkflow`。

**3. Type consistency**
- 键常量 `KEY_QUERY/KEY_VECTOR/KEY_FULLTEXT/KEY_FUZZY/KEY_FUSED/KEY_FILTERED` 定义于 Task 4，被 Task 1–3 的测试与实现引用（任务依赖允许 Task 4 提前定义常量的实现顺序）✓
- `execute(RagQuery)` 返回 `List<RagResult.ChunkResult>` 在 Task 4/5 一致 ✓
- 加工后的实现弃用 `AgentState`、`toMap()` override、`this.data` 直访等不可行方案，统一改用 `OverAllState.value()` / `invoke(OverAllState)` ✓

> 说明：因三路检索节点、融合、筛选节点的测试在实现顺序上依赖 Task 4 才存在的键常量，最终验收统一为「本模块 `mvn test` 相关 4 个测试类全绿」+「模块 compile 通过」。任务实施时允许就近调整实现细节以可编译为准，但保持对外行为与状态键名不变。
