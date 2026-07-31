package com.company.rag.agent.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 处理结果
 */
@Data
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
}
