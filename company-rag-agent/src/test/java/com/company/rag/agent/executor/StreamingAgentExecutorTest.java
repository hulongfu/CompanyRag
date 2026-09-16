package com.company.rag.agent.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.company.rag.agent.service.AgentResult;
import com.company.rag.common.tool.ToolCallRecorder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

class StreamingAgentExecutorTest {

    private ReactAgent reactAgent;
    private ToolCallRecorder recorder;
    private StreamingAgentExecutor executor;

    @BeforeEach
    void setUp() {
        reactAgent = mock(ReactAgent.class);
        recorder = new ToolCallRecorder();
        executor = new StreamingAgentExecutor(reactAgent, recorder);
    }

    @Test
    void execute_returnsRealToolContext() throws Exception {
        when(reactAgent.call(List.of(new UserMessage("hi"))))
                .thenReturn(new AssistantMessage("hello"));

        long start = recorder.recordStart("searchKnowledgeBase", java.util.Map.of("question", "q"));
        recorder.recordEnd("searchKnowledgeBase", start, "success", null, "citations=c1");

        AgentResult result = executor.execute(List.of(new UserMessage("hi")));

        assertEquals("hello", result.getAnswer());
        assertEquals("searchKnowledgeBase:citations=c1", result.getToolContext());
    }

    @Test
    void execute_toolContextEmpty_whenNoToolsCalled() throws Exception {
        when(reactAgent.call(List.of(new UserMessage("hi"))))
                .thenReturn(new AssistantMessage("hello"));

        AgentResult result = executor.execute(List.of(new UserMessage("hi")));

        assertEquals("", result.getToolContext());
    }

    @Test
    void execute_clearsRecords_afterCapture() throws Exception {
        when(reactAgent.call(List.of(new UserMessage("hi"))))
                .thenReturn(new AssistantMessage("hello"));

        long start = recorder.recordStart("searchKnowledgeBase", java.util.Map.of("question", "q"));
        recorder.recordEnd("searchKnowledgeBase", start, "success", null, "citations=c1");

        executor.execute(List.of(new UserMessage("hi")));

        // execute 内部在捕获上下文后用 finally 清理工作线程的记录，避免串号/泄漏
        assertEquals("", recorder.captureToolContext());
    }
}