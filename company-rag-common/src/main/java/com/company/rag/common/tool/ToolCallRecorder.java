package com.company.rag.common.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工具调用记录器（通用组件）
 * 支持 traceId 追踪、耗时记录、调用链路聚合
 */
@Slf4j
@Component
public class ToolCallRecorder {

    private static final int MAX_INPUT_LENGTH = 50;

    private final ThreadLocal<List<ToolCallRecord>> recordsHolder = new ThreadLocal<>();
    private final ThreadLocal<String> traceIdHolder = new ThreadLocal<>();

    /**
     * 生成 traceId
     */
    public String generateTraceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 设置当前线程的 traceId（在 RagAgentService 中调用）
     */
    public void setTraceId(String traceId) {
        traceIdHolder.set(traceId);
    }

    /**
     * 获取当前线程的 traceId（在工具方法中调用）
     */
    public String getTraceId() {
        return traceIdHolder.get();
    }

    /**
     * 清除当前线程的 traceId
     */
    public void clearTraceId() {
        traceIdHolder.remove();
    }

    /**
     * 记录工具调用开始
     * @return 开始时间戳（毫秒）
     */
    public long recordStart(String traceId, String toolName, Map<String, Object> arguments) {
        String inputSummary = arguments != null
                ? arguments.toString().substring(0, Math.min(arguments.toString().length(), MAX_INPUT_LENGTH))
                : "";
        log.info("[TOOL_START] traceId={}, tool={}, input={}", traceId, toolName, inputSummary);
        return System.currentTimeMillis();
    }

    /**
     * 记录工具调用结束
     */
    public void recordEnd(String traceId, String toolName, long startTimeMs, String status) {
        recordEnd(traceId, toolName, startTimeMs, status, null);
    }

    /**
     * 记录工具调用结束（带错误信息）
     */
    public void recordEnd(String traceId, String toolName, long startTimeMs, String status, String errorMessage) {
        long durationMs = System.currentTimeMillis() - startTimeMs;

        ToolCallRecord record = ToolCallRecord.builder()
                .traceId(traceId)
                .toolName(toolName)
                .durationMs(durationMs)
                .status(status)
                .errorMessage(errorMessage)
                .build();

        List<ToolCallRecord> records = recordsHolder.get();
        if (records == null) {
            records = new ArrayList<>();
            recordsHolder.set(records);
        }
        records.add(record);

        if (errorMessage != null) {
            log.warn("[TOOL_END] traceId={}, tool={}, duration={}ms, status={}, error={}",
                    traceId, toolName, durationMs, status, errorMessage);
        } else {
            log.info("[TOOL_END] traceId={}, tool={}, duration={}ms, status={}",
                    traceId, toolName, durationMs, status);
        }
    }

    /**
     * 获取并清除本次请求的所有工具调用记录
     */
    public List<ToolCallRecord> getAndClearRecords(String traceId) {
        List<ToolCallRecord> records = recordsHolder.get();
        recordsHolder.remove();
        if (records == null) {
            return List.of();
        }
        return records;
    }
}