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
import com.company.rag.tenant.context.TenantContextSnapshot;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class NormalizeFuseNodeTest {

    @Test
    void writesFusedResult() throws Exception {
        RagQuery query = new RagQuery();
        query.setQuery("q");
        Map<String, Object> m = new HashMap<>();
        m.put(WorkflowKeys.QUERY, query);
        m.put(WorkflowKeys.VECTOR_CHUNKS,
                Collections.singletonList(new RagResult.ChunkResult()));
        m.put(WorkflowKeys.FULLTEXT_CHUNKS, Collections.emptyList());
        m.put(WorkflowKeys.FUZZY_CHUNKS, Collections.emptyList());
        OverAllState state = new OverAllState(m);

        RankNormalizer normalizer = mock(RankNormalizer.class);
        when(normalizer.normalize(anyList()))
                .thenReturn(Collections.singletonList(new NormalizedResult()));
        ResultFuser fuser = mock(ResultFuser.class);
        when(fuser.fuse(anyList(), anyList(), anyList(), anyString()))
                .thenReturn(Collections.singletonList(new FusedResult()));

        NormalizeFuseNode node = new NormalizeFuseNode(normalizer, fuser);
        Map<String, Object> out = node.apply(state).get();

        assertEquals(1, ((List<?>) out.get(WorkflowKeys.FUSED)).size());
    }

    @AfterEach
    void clearMdc() {
        MDC.remove("traceId");
        MDC.remove("spanId");
    }

    /**
     * 验证融合节点在工作线程内能恢复请求线程的日志链路标识。
     *
     * <p>融合节点运行在图引擎工作线程，日志 MDC 不跨线程。本用例通过状态中的快照
     * 验证 apply() 后 worker 线程内读到与请求线程一致的 traceId，修复融合日志 traceId 为空。</p>
     */
    @Test
    void mdcTraceContextPropagatedToFuseNodeWorkerThread() throws Exception {
        // 请求线程设置日志链路标识
        MDC.put("traceId", "trace-fuse-999");
        MDC.put("spanId", "span-fuse-111");
        TenantContextSnapshot snapshot = TenantContextSnapshot.captureNow();

        RagQuery query = new RagQuery();
        query.setQuery("q");
        Map<String, Object> m = new HashMap<>();
        m.put(WorkflowKeys.QUERY, query);
        m.put(WorkflowKeys.TENANT_CONTEXT, snapshot);
        m.put(WorkflowKeys.VECTOR_CHUNKS, Collections.emptyList());
        m.put(WorkflowKeys.FULLTEXT_CHUNKS, Collections.emptyList());
        m.put(WorkflowKeys.FUZZY_CHUNKS, Collections.emptyList());
        OverAllState state = new OverAllState(m);

        RankNormalizer normalizer = mock(RankNormalizer.class);
        when(normalizer.normalize(anyList()))
                .thenReturn(Collections.emptyList());
        ResultFuser fuser = mock(ResultFuser.class);
        // 在工作线程内读取本线程 MDC 链路标识
        AtomicReference<String> traceSeen = new AtomicReference<>();
        when(fuser.fuse(anyList(), anyList(), anyList(), anyString()))
                .thenAnswer(inv -> {
                    traceSeen.set(MDC.get("traceId"));
                    return Collections.emptyList();
                });

        NormalizeFuseNode node = new NormalizeFuseNode(normalizer, fuser);
        node.apply(state).get();

        assertEquals("trace-fuse-999", traceSeen.get());
    }
}
