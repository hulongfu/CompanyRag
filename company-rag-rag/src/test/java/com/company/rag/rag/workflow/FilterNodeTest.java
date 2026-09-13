package com.company.rag.rag.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterNodeTest {

    @Test
    void writesFilteredOutput() throws Exception {
        RagQuery query = new RagQuery();
        query.setFusionTopK(30);
        query.setScoreThreshold(null);
        Map<String, Object> m = new HashMap<>();
        m.put(WorkflowKeys.QUERY, query);
        FusedResult fr = new FusedResult();
        fr.setChunkId("c1");
        fr.setFinalScore(0.8);
        m.put(WorkflowKeys.FUSED, Collections.singletonList(fr));
        OverAllState state = new OverAllState(m);

        ResultFilter filter = mock(ResultFilter.class);
        when(filter.filter(anyList(), anyInt(), any()))
                .thenAnswer(inv -> inv.getArgument(0));

        FilterNode node = new FilterNode(filter);
        Map<String, Object> out = node.apply(state).get();
        assertEquals(1, ((List<?>) out.get(WorkflowKeys.FILTERED)).size());
    }

    @Test
    void emptyFusedYieldsEmptyOutput() throws Exception {
        Map<String, Object> m = new HashMap<>();
        m.put(WorkflowKeys.QUERY, new RagQuery());
        m.put(WorkflowKeys.FUSED, Collections.emptyList());
        OverAllState state = new OverAllState(m);

        ResultFilter filter = mock(ResultFilter.class);
        when(filter.filter(anyList(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

        FilterNode node = new FilterNode(filter);
        Map<String, Object> out = node.apply(state).get();
        assertEquals(0, ((List<?>) out.get(WorkflowKeys.FILTERED)).size());
    }
}
