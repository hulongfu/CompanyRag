package com.company.rag.common.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallRecorderTest {

    private ToolCallRecorder recorder;

    @BeforeEach
    void setUp() {
        recorder = new ToolCallRecorder();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void recordStart_getsTraceIdFromMdc() {
        MDC.put("traceId", "trace-1");
        long start = recorder.recordStart("testTool", Map.of("key", "value"));
        assertTrue(start > 0);
    }

    @Test
    void recordStart_withNullArgs_returnsPositiveStart() {
        MDC.put("traceId", "trace-1");
        long start = recorder.recordStart("testTool", null);
        assertTrue(start > 0);
    }

    @Test
    void recordEnd_aggregatesRecordWithMdcTraceId() {
        MDC.put("traceId", "trace-1");
        long start = recorder.recordStart("testTool", Map.of("k", "v"));
        recorder.recordEnd("testTool", start, "success");

        List<ToolCallRecord> records = recorder.getAndClearRecords();
        assertEquals(1, records.size());
        ToolCallRecord rec = records.get(0);
        assertEquals("trace-1", rec.getTraceId());
        assertEquals("testTool", rec.getToolName());
        assertEquals("success", rec.getStatus());
    }

    @Test
    void recordEnd_withErrorMessage_recordsWarn() {
        MDC.put("traceId", "trace-2");
        long start = recorder.recordStart("testTool", null);
        recorder.recordEnd("testTool", start, "failed", "boom");

        List<ToolCallRecord> records = recorder.getAndClearRecords();
        assertEquals(1, records.size());
        assertEquals("boom", records.get(0).getErrorMessage());
        assertEquals("failed", records.get(0).getStatus());
    }

    @Test
    void getAndClearRecords_clearsAfterRetrieve() {
        MDC.put("traceId", "trace-3");
        long start = recorder.recordStart("testTool", null);
        recorder.recordEnd("testTool", start, "success");

        List<ToolCallRecord> first = recorder.getAndClearRecords();
        assertEquals(1, first.size());
        assertTrue(recorder.getAndClearRecords().isEmpty());
    }

    @Test
    void getAndClearRecords_noMdcReturnsEmpty() {
        assertTrue(recorder.getAndClearRecords().isEmpty());
    }
}