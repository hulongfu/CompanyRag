package com.company.rag.rag.router;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 意图识别结果
 * 包含识别出的意图、来源和置信度
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentResult {
    /**
     * 识别出的意图类型
     */
    private IntentType intent;

    /**
     * 识别来源（如：llm、rule、model）
     */
    private String source;

    /**
     * 置信度（0.0 - 1.0）
     */
    private Double confidence;
}
