package com.company.rag.rag.response;

import com.company.rag.rag.router.IntentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天指标类
 * 记录请求的性能指标和路由信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMetrics {
    /**
     * 总耗时（毫秒）
     */
    private long totalMs;

    /**
     * Token 使用情况
     */
    private int tokens;

    /**
     * 识别出的意图类型
     */
    private IntentType intent;

    /**
     * 路由路径
     */
    private String routePath;
}
