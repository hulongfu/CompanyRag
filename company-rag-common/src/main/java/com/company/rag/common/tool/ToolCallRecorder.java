package com.company.rag.common.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工具调用记录器（通用组件）
 */
@Slf4j
@Component
public class ToolCallRecorder {
    
    /**
     * 记录工具调用开始
     */
    public void recordStart(String toolName, Map<String, Object> arguments) {
        log.info("工具调用开始：tool={}, arguments={}", toolName, arguments);
    }
    
    /**
     * 记录工具调用结束
     */
    public void recordEnd(String toolName, String status) {
        log.info("工具调用结束：tool={}, status={}", toolName, status);
    }
    
    /**
     * 记录工具调用异常
     */
    public void recordError(String toolName, String error) {
        log.error("工具调用异常：tool={}, error={}", toolName, error);
    }
}
