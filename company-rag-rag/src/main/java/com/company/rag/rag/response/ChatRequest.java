package com.company.rag.rag.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求类
 * 统一聊天接口的请求参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {
    /**
     * 用户查询内容
     */
    private String query;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 租户 ID
     */
    private Long tenantId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 返回结果数量（默认 10）
     */
    private Integer topK = 10;

    /**
     * 是否启用 Rerank（默认 true）
     */
    private Boolean enableRerank = true;

    /**
     * 是否包含调试信息（默认 false）
     */
    private Boolean includeDebug = false;

    /**
     * 模式（如：search、chat、agent）
     */
    private String mode;
}
