package com.company.rag.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 审批门配置（全局开关，非 per-tool）。
 * <p>
 * enabled 为总开关：默认关闭时全部工具直接执行、不走审批门，保证向后兼容。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.approval")
public class ApprovalProperties {

    /** 总开关：默认关闭。关闭时全部工具直接执行、不走审批门。 */
    private boolean enabled = false;

    /** 同步等待人工审批的上限（秒）。默认 300，与 Agent 整体 5 分钟超时对齐；超时自动转 DENIED。 */
    private long timeoutSeconds = 300;

    /** 轮询 DB 状态间隔（毫秒），避免等待期间忙等。 */
    private long pollIntervalMs = 500;

    /** 高危工具兜底集（逗号分隔工具名）：即使 requiresApproval=false 也强制审批。 */
    private String highRiskTools = "execute";
}