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
}
