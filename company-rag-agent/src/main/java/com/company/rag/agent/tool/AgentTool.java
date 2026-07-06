package com.company.rag.agent.tool;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Agent工具接口
 * 所有Agent工具必须实现此接口
 */
public interface AgentTool {
    
    /**
     * 获取工具名称
     */
    String getName();
    
    /**
     * 获取工具描述
     */
    String getDescription();
    
    /**
     * 获取参数Schema
     */
    Map<String, Object> getParameterSchema();
    
    /**
     * 执行工具
     * @param params 参数Map
     * @return 执行结果
     */
    String execute(Map<String, Object> params);
}
