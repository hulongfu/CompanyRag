package com.company.rag.common.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具调用记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRecord {
    private String traceId;
    private String toolName;
    private long durationMs;
    private String status;       // success / failed
    private String errorMessage;
}