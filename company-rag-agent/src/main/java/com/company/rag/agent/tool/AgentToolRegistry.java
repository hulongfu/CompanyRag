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

    public AgentToolRegistry(List<AgentTool> toolList) {
        // 自动注册所有AgentTool实现
        for (AgentTool tool : toolList) {
            register(tool);
        }
        log.info("已注册{}个Agent工具: {}", tools.size(), tools.keySet());
    }

    /**
     * 注册工具
     */
    public void register(AgentTool tool) {
        tools.put(tool.getName(), tool);
        log.debug("注册Agent工具: {}", tool.getName());
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
}
