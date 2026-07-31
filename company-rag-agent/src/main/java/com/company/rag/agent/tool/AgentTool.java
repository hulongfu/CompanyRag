package com.company.rag.agent.tool;

import java.util.Map;

/**
 * Agent 工具接口
 * 所有 Agent 工具都需要实现此接口
 */
public interface AgentTool {
    
    /**
     * 工具名称（用于 LLM 识别）
     */
    String getName();
    
    /**
     * 工具描述（帮助 LLM 理解工具用途）
     */
    String getDescription();
    
    /**
     * 参数 Schema 定义
     * @return JSON Schema 格式的参数定义
     */
    Map<String, Object> getParameterSchema();
    
    /**
     * 执行工具
     * @param params 参数 Map
     * @return 工具执行结果
     */
    String execute(Map<String, Object> params);
}
