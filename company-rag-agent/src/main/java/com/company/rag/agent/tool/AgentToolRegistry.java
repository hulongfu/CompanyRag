package com.company.rag.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Agent工具注册中心
 * 统一管理所有可用的Agent工具
 */
@Slf4j
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> tools = new HashMap<>();
    private volatile int version = 0; // 工具列表版本号，每次工具变更时递增

    public AgentToolRegistry(List<AgentTool> toolList) {
        // 自动注册所有 AgentTool 实现
        for (AgentTool tool : toolList) {
            register(tool);
        }
        log.info("已注册{}个 Agent 工具：{}", tools.size(), tools.keySet());
    }

    /**
     * 注册工具
     */
    public void register(AgentTool tool) {
        tools.put(tool.getName(), tool);
        version++; // 递增版本号
        log.debug("注册 Agent 工具：{} (version={})", tool.getName(), version);
    }

    /**
     * 获取工具
     */
    public AgentTool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 列出所有工具
     */
    public List<Map<String, Object>> listTools() {
        return tools.values().stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.getName(),
                        "description", tool.getDescription(),
                        "parameters", tool.getParameterSchema()
                ))
                .toList();
    }

    /**
     * 执行工具
     */
    public String executeTool(String name, Map<String, Object> params) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return "错误：工具不存在: " + name;
        }
        
        try {
            log.info("执行Agent工具: {} | params={}", name, params);
            return tool.execute(params);
        } catch (Exception e) {
            log.error("工具执行失败: {} | error={}", name, e.getMessage(), e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }
    
    /**
     * 获取当前工具列表版本号
     * @return 版本号，每次工具注册或变更时递增
     */
    public int getVersion() {
        return version;
    }
    
    /**
     * 获取所有已注册的工具
     * @return 工具 Map 的只读视图
     */
    public Map<String, AgentTool> getAllTools() {
        return Collections.unmodifiableMap(tools);
    }
}
