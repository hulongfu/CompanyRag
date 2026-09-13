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
        when(filter.filter(anyList(), anyInt(), org.mockito.ArgumentMatchers.any()))
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
