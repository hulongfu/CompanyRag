package com.company.rag.agent.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 处理结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {
    /**
     * Agent 生成的回答
     */
    private String answer;

    /**
     * 工具上下文信息（如调用了什么工具）
     */
    private String toolContext;

    /**
     * 本次执行是否调用了知识库检索工具（searchKnowledgeBase）。
     * 在线评估只对真实执行过 RAG 的回答进行，非 RAG 回复的 faithfulness
     * 因缺少检索上下文会恒判 0 分，故需要该标志位来过滤。
     */
    private boolean ragUsed;
}