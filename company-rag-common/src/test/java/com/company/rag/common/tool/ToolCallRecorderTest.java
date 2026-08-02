package com.company.rag.common.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallRecorderTest {

    private ToolCallRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ToolCallRecorder();
    }

    @Test
    void shouldGenerateTraceId() {
        String traceId = recorder.generateTraceId();
        assertNotNull(traceId);
        assertEquals(8, traceId.length());
    }

    @Test
    void shouldRecordToolCall() {
        String traceId = "test123";
        recorder.setTraceId(traceId);

        long startTime = recorder.recordStart(traceId, "testTool", Map.of("key", "value"));

        recorder.recordEnd(traceId, "testTool", startTime, "success");

        List<ToolCallRecord> records = recorder.getAndClearRecords(traceId);
        assertEquals(1, records.size());
        assertEquals("testTool", records.get(0).getToolName());
        assertEquals("success", records.get(0).getStatus());
        assertTrue(records.get(0).getDurationMs() >= 0);
    }

    @Test
    void shouldRecordMultipleTools() {
        String traceId = "test456";
        recorder.setTraceId(traceId);

        long start1 = recorder.recordStart(traceId, "tool1", null);
        recorder.recordEnd(traceId, "tool1", start1, "success");

        long start2 = recorder.recordStart(traceId, "tool2", null);
        recorder.recordEnd(traceId, "tool2", start2, "failed", "error msg");

        List<ToolCallRecord> records = recorder.getAndClearRecords(traceId);
        assertEquals(2, records.size());
        assertEquals("tool1", records.get(0).getToolName());
        assertEquals("tool2", records.get(1).getToolName());
        assertEquals("error msg", records.get(1).getErrorMessage());
    }

    @Test
    void shouldReturnEmptyListWhenNoRecords() {
        List<ToolCallRecord> records = recorder.getAndClearRecords("notrace");
        assertTrue(records.isEmpty());
    }

    @Test
    void shouldClearRecordsAfterGet() {
        String traceId = "test789";
        recorder.setTraceId(traceId);

        long start = recorder.recordStart(traceId, "tool", null);
        recorder.recordEnd(traceId, "tool", start, "success");

        recorder.getAndClearRecords(traceId);

        // 再次获取应该为空
        List<ToolCallRecord> records = recorder.getAndClearRecords(traceId);
        assertTrue(records.isEmpty());
    }
}