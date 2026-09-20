package com.company.rag.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.rag.agent.executor.StreamingAgentExecutor;
import com.company.rag.common.tool.ToolCallRecorder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RagAgentServiceTest {

    private StreamingAgentExecutor streamingAgentExecutor;
    private ToolCallRecorder recorder;
    private RagAgentService service;

    @BeforeEach
    void setUp() {
        streamingAgentExecutor = mock(StreamingAgentExecutor.class);
        recorder = mock(ToolCallRecorder.class);
        // processWithHistory 会调用 recorder.getAndClearRecords() 并遍历，未调用工具时返回空表，避免 NPE
        when(recorder.getAndClearRecords()).thenReturn(List.of());
        service = new RagAgentService(streamingAgentExecutor, recorder, 1, 2, 10, 5);
    }

    @Test
    void processWithHistory_returnsRealToolContext() throws Exception {
        when(streamingAgentExecutor.execute(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new AgentResult("answer", "searchKnowledgeBase:citations=c1"));

        AgentResult result = service.processWithHistory(null, "hi");

        assertEquals("answer", result.getAnswer());
        assertEquals("searchKnowledgeBase:citations=c1", result.getToolContext());
    }
}