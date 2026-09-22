package com.company.rag.mcp.client;

import com.company.rag.common.model.AuditLogContext;
import com.company.rag.common.service.AuditLogService;
import com.company.rag.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 健康调度器。
 * - 周期探活(health-check-interval-ms)已注册客户端，失联按源移除全部工具。
 * - 周期重连(reconnect-interval-ms)失败清单中的客户端，成功后重新注册。
 * fixedDelay 保证单方法串行执行、轮次不重叠，避免重连风暴。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpHealthScheduler {

    /** 启动失败端点（未进入 clients 但从不健康配置重建），供状态查询补全 */
    private final Map<String, LocalDateTime> startupFailures = new ConcurrentHashMap<>();

    private final McpClientRegistry registry;
    private final McpClientProperties properties;
    private final AuditLogService auditLogService;

    public McpHealthScheduler(McpClientRegistry registry, McpClientProperties properties, AuditLogService auditLogService) {
        this.registry = registry;
        this.properties = properties;
        this.auditLogService = auditLogService;
    }

    /** 周期探活（默认 10 分钟，兜底） */
    @Scheduled(fixedDelayString = "${mcp.health-check-interval-ms:600000}")
    public void probeConnectedClients() {
        log.debug("MCP 周期探活开始");
        for (Map.Entry<String, McpClient> entry : registry.getClients().entrySet()) {
            String clientId = entry.getKey();
            McpClient client = entry.getValue();
            boolean ok;
            try {
                ok = client.ping();
            } catch (Exception e) {
                ok = false;
                log.warn("MCP Client [{}] 探活异常", clientId, e);
            }
            if (!ok) {
                log.warn("MCP Client [{}] 探活失败，按源移除全部工具", clientId);
                registry.removeToolsFor(clientId);
                audit("MCP_REMOVE_SOURCE", clientId, "周期探活失败，移除该源全部工具");
            } else {
                registry.markReachable(clientId);
                log.debug("MCP Client [{}] 探活正常", clientId);
            }
        }
        // 清理已恢复的启动失败标记
        startupFailures.keySet().retainAll(registry.getClients().keySet());
        log.debug("MCP 周期探活完成");
    }

    /** 重连失败清单（默认 3 分钟一次） */
    @Scheduled(fixedDelayString = "${mcp.reconnect-interval-ms:180000}")
    public void reconnectFailedClients() {
        Set<String> failed = registry.getFailedClients();
        if (failed.isEmpty()) {
            return;
        }
        log.info("MCP 尝试重连失败的客户端：{}", failed);
        for (String clientId : failed) {
            McpClientProperties.ClientConfig config = findConfig(clientId);
            if (config == null) {
                log.warn("MCP 找不到客户端 [{}] 的配置，跳过重连", clientId);
                continue;
            }
            try {
                HttpMcpClient client = new HttpMcpClient(
                        config.getId(), config.getUrl(), config.getTimeout(), config.getHeaders());
                // registerClient 先放入 clients 再 connect，最终成功以是否仍留在失败清单为准
                registry.registerClient(config.getId(), client);
                if (!registry.getFailedClients().contains(config.getId())) {
                    startupFailures.remove(config.getId());
                    audit("MCP_RECONNECT", config.getId(), "重连成功并重新注册工具，url=" + config.getUrl());
                }
            } catch (Exception e) {
                log.warn("MCP Client [{}] 重连失败，稍后重试：{}", clientId, e.getMessage());
            }
        }
    }

    /** 从配置定位客户端（兼容来自启动失败的端点） */
    private McpClientProperties.ClientConfig findConfig(String clientId) {
        for (McpClientProperties.ClientConfig c : properties.getClients()) {
            if (clientId.equals(c.getId()) && c.isEnabled()) {
                return c;
            }
        }
        return null;
    }

    /** 记录某个启动失败端点（由 McpClientAutoConfig 在连接异常时调用） */
    public void recordStartupFailure(String clientId) {
        startupFailures.put(clientId, LocalDateTime.now());
    }

    /** 供状态查询合并所有已知端点 */
    public Map<String, LocalDateTime> getStartupFailures() {
        return startupFailures;
    }

    private void audit(String actionType, String clientId, String detail) {
        try {
            auditLogService.recordAsync(AuditLogContext.builder()
                    .actionType(actionType).targetType("mcp").targetId(clientId).detail(detail)
                    .tenantId(TenantContext.getTenantId() != null ? String.valueOf(TenantContext.getTenantId()) : null)
                    .userId(TenantContext.getUserId()).build());
        } catch (Exception e) {
            log.warn("MCP 调度审计失败：clientId={}", clientId, e);
        }
    }
}