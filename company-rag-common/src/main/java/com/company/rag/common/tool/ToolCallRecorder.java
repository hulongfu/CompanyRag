package com.company.rag.common.tool;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工具调用记录器（通用组件）
 * traceId 由 Micrometer Tracing 自动写入 MDC，此处仅负责记录工具调用的耗时与状态
 */
@Slf4j
@Component
public class ToolCallRecorder {

    private static final int MAX_INPUT_LENGTH = 50;

    private final ThreadLocal<List<ToolCallRecord>> recordsHolder = new ThreadLocal<>();

    /**
     * 记录工具调用开始，traceId 从 MDC 读取
     * @return 开始时间戳（毫秒）
     */
    public long recordStart(String toolName, Map<String, Object> arguments) {
        String traceId = traceIdFromMdc();
        String inputSummary = arguments != null
                ? arguments.toString().substring(0, Math.min(arguments.toString().length(), MAX_INPUT_LENGTH))
                : "";
        log.info("[TOOL_START] traceId={}, tool={}, input={}", traceId, toolName, inputSummary);
        return System.currentTimeMillis();
    }

    /**
     * 记录工具调用结束
     */
    public void recordEnd(String toolName, long startTimeMs, String status) {
        recordEnd(toolName, startTimeMs, status, null);
    }

    /**
     * 记录工具调用结束（带错误信息）
     */
    public void recordEnd(String toolName, long startTimeMs, String status, String errorMessage) {
        String traceId = traceIdFromMdc();
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
    public List<ToolCallRecord> getAndClearRecords() {
        List<ToolCallRecord> records = recordsHolder.get();
        recordsHolder.remove();
        if (records == null) {
            return List.of();
        }
        return records;
    }

    /**
     * 从 MDC 读取当前 traceId，获取不到时返回空串（避免拼 null）
     */
    private String traceIdFromMdc() {
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : "";
    }
}