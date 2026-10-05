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

    /**
     * 该工具调用是否需要经过人类审批门。
     * <p>
     * 默认返回 false（不审批）；有外部副作用 / 动作类 / 高风险的工具重写返回 true。
     * 最终判定 = 本方法 OR 高危兜底集（由 {@code ToolApprovalService} 维护，如 execute 强制拦截），
     * 以保证即使个别工具漏声明也不至于默认放行。
     *
     * @return true 表示该工具调用需先通过人工审批
     */
    default boolean requiresApproval() {
        return false;
    }
}
