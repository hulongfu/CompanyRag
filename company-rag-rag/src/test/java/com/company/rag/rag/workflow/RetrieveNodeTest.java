package com.company.rag.rag.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.retriever.impl.FullTextRetriever;
import com.company.rag.rag.retriever.impl.FuzzyRetriever;
import com.company.rag.rag.retriever.impl.VectorRetriever;
import com.company.rag.tenant.context.TenantContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RetrieveNodeTest {

    private static OverAllState stateWith(RagQuery query) {
        Map<String, Object> m = new HashMap<>();
        m.put(WorkflowKeys.QUERY, query);
        m.put(WorkflowKeys.VECTOR_CHUNKS, Collections.emptyList());
        m.put(WorkflowKeys.FULLTEXT_CHUNKS, Collections.emptyList());
        m.put(WorkflowKeys.FUZZY_CHUNKS, Collections.emptyList());
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

        assertEquals(1, ((List<?>) out.get(WorkflowKeys.VECTOR_CHUNKS)).size());
    }

    @Test
    void fullTextNodeWritesChunks() throws Exception {
        FullTextRetriever retriever = mock(FullTextRetriever.class);
        when(retriever.retrieve(anyString(), anyInt()))
                .thenReturn(Collections.singletonList(new RagResult.ChunkResult()));
        FullTextRetrieveNode node = new FullTextRetrieveNode(retriever);
        RagQuery query = new RagQuery();
        query.setQuery("q");
        Map<String, Object> out = node.apply(stateWith(query)).get();
        assertEquals(1, ((List<?>) out.get(WorkflowKeys.FULLTEXT_CHUNKS)).size());
    }

    @Test
    void fuzzyNodeWritesChunks() throws Exception {
        FuzzyRetriever retriever = mock(FuzzyRetriever.class);
        when(retriever.retrieve(anyString(), anyInt()))
                .thenReturn(Collections.singletonList(new RagResult.ChunkResult()));
        FuzzyRetrieveNode node = new FuzzyRetrieveNode(retriever);
        RagQuery query = new RagQuery();
        query.setQuery("q");
        Map<String, Object> out = node.apply(stateWith(query)).get();
        assertEquals(1, ((List<?>) out.get(WorkflowKeys.FUZZY_CHUNKS)).size());
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
        assertEquals(0, ((List<?>) out.get(WorkflowKeys.VECTOR_CHUNKS)).size());
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /**
     * 验证 StateGraph 工作线程内能读取到请求线程捕获的租户上下文快照。
     *
     * <p>节点在 ForkJoinPool 工作线程执行，TenantContext 基于 ThreadLocal 不跨线程，
     * 本用例验证节点通过状态中的快照写回租户 schema 后，检索器可正常读到。</p>
     */
    @Test
    void tenantContextPropagatedToWorkerThread() throws Exception {
        // 请求线程设置租户上下文
        TenantContext.setTenantId(1L);
        TenantContext.setTenantCode("tenant-001");
        TenantContext.setSchema("t_tenant_001");
        TenantContext.setSessionId("sess-1");
        TenantContextSnapshot snapshot = TenantContextSnapshot.captureNow();

        RagQuery query = new RagQuery();
        query.setQuery("q");

        // 检索器在节点apply的工作线程内执行，此处通过doAnswer读取实际线程的租户上下文
        AtomicReference<String> schemaSeen = new AtomicReference<>();
        VectorRetriever retriever = mock(VectorRetriever.class);
        doAnswer(inv -> {
            schemaSeen.set(TenantContext.getSchema());
            return Collections.singletonList(new RagResult.ChunkResult());
        }).when(retriever).retrieve(anyString(), anyInt());

        // 在另一线程模拟图节点 apply（ForkJoinPool 风格）
        Map<String, Object> stateMap = new HashMap<>();
        stateMap.put(WorkflowKeys.QUERY, query);
        stateMap.put(WorkflowKeys.TENANT_CONTEXT, snapshot);
        stateMap.put(WorkflowKeys.VECTOR_CHUNKS, Collections.emptyList());

        VectorRetrieveNode node = new VectorRetrieveNode(retriever);
        node.apply(new OverAllState(stateMap)).get();

        // 工作线程内应读到请求线程的 schema
        assertEquals("t_tenant_001", schemaSeen.get());
    }
}
