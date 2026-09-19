package com.company.rag.rag.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.company.rag.tenant.context.TenantContextSnapshot;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

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
        MDC.remove("traceId");
        MDC.remove("spanId");
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

    /**
     * 验证状态中的快照会把请求线程日志 MDC 的 traceId/spanId 恢复到工作线程。
     *
     * <p>图并行节点运行在内部线程池，日志 MDC 基于 ThreadLocal 不跨线程，
     * 本用例验证节点通过 apply() 写回 MDC 后，工作线程内的日志链路标识与请求线程一致，
     * 从而修复日志中 traceId=/spanId= 为空的问题。</p>
     */
    @Test
    void mdcTraceContextPropagatedToWorkerThread() throws Exception {
        // 请求线程设置日志链路标识（模拟 Micrometer TracingObservationHandler 写入 MDC）
        MDC.put("traceId", "trace-abc-123");
        MDC.put("spanId", "span-xyz-456");
        TenantContextSnapshot snapshot = TenantContextSnapshot.captureNow();

        RagQuery query = new RagQuery();
        query.setQuery("q");

        // 检索器在工作线程内执行，读取该线程的 MDC 链路标识
        AtomicReference<String> traceSeen = new AtomicReference<>();
        AtomicReference<String> spanSeen = new AtomicReference<>();
        VectorRetriever retriever = mock(VectorRetriever.class);
        doAnswer(inv -> {
            traceSeen.set(MDC.get("traceId"));
            spanSeen.set(MDC.get("spanId"));
            return Collections.singletonList(new RagResult.ChunkResult());
        }).when(retriever).retrieve(anyString(), anyInt());

        Map<String, Object> stateMap = new HashMap<>();
        stateMap.put(WorkflowKeys.QUERY, query);
        stateMap.put(WorkflowKeys.TENANT_CONTEXT, snapshot);
        stateMap.put(WorkflowKeys.VECTOR_CHUNKS, Collections.emptyList());

        VectorRetrieveNode node = new VectorRetrieveNode(retriever);
        node.apply(new OverAllState(stateMap)).get();

        // worker 线程内应读到请求线程的 traceId/spanId
        assertEquals("trace-abc-123", traceSeen.get());
        assertEquals("span-xyz-456", spanSeen.get());
    }

    /**
     * 验证请求线程未启用追踪（MDC 无链路标识）时，快照写回不会产生脏数据。
     */
    @Test
    void mdcTraceContextAbsentIsNoop() throws Exception {
        // 不设置任何 MDC 链路标识
        TenantContextSnapshot snapshot = TenantContextSnapshot.captureNow();
        snapshot.apply();
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("spanId"));
    }
}
