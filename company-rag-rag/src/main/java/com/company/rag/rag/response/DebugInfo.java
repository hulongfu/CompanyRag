package com.company.rag.rag.response;

import com.company.rag.rag.router.IntentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 调试信息类
 * 提供请求处理的详细调试信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebugInfo {
    /**
     * 识别出的意图类型
     */
    private IntentType intent;

    /**
     * 识别来源（如：llm、rule、model）
     */
    private String recognizeSource;

    /**
     * 置信度（0.0 - 1.0）
     */
    private Double confidence;

    /**
     * 使用的工具
     */
    private String toolUsed;

    /**
     * 路由路径
     */
    private String routePath;

    /**
     * 检索到的来源列表
     */
    private List<String> sources;
}
