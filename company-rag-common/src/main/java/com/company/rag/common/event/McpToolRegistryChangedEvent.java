package com.company.rag.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * MCP 工具注册表变更事件
 * <p>
 * 当某个 MCP Client 的工具集发生变化（新注册 / 同步 / 按源移除）时发布，
 * 供各模块（如 Agent 工具列表）补偿刷新，使运行时动态加载的工具对模型可见。
 */
@Getter
public class McpToolRegistryChangedEvent extends ApplicationEvent {

    /** 变更来源的 MCP Client ID */
    private final String clientId;

    public McpToolRegistryChangedEvent(Object source, String clientId) {
        super(source);
        this.clientId = clientId;
    }
}
