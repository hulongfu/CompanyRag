package com.company.rag.rag.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天响应类
 * 统一聊天接口的响应结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    /**
     * AI 生成的回答
     */
    private String answer;

    /**
     * 引用来源列表
     */
    private List<String> sources;

    /**
     * 性能指标
     */
    private ChatMetrics metrics;

    /**
     * 调试信息（可选）
     */
    private DebugInfo debug;
}
