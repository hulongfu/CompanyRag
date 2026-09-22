package com.company.rag.mcp.client;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MCP 端点运行状态（内存态，用于管理员状态查询）。
 */
@Data
@Builder
public class McpEndpointStatus {

    private String clientId;
    private String url;
    private boolean enabled;
    private boolean connected;
    private int toolCount;
    /** 记录进入失败态的时间；null 表示当前不在失败态 */
    private LocalDateTime failedSince;
    private LocalDateTime lastProbeTime;
    /** 最近一次探活/操作的简要结果描述 */
    private String lastProbeResult;
    /** 该源已注册进 Agent 的工具名 */
    private List<String> registeredToolNames;
}