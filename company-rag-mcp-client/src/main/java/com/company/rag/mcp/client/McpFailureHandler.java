package com.company.rag.mcp.client;

import com.company.rag.common.model.AuditLogContext;
import com.company.rag.common.service.AuditLogService;
import com.company.rag.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MCP 调用失败即时自愈处理。
 * 规则：参数错误 → 忽略；非参数错误 → 命中 MCP 源 → 远端可达性：
 *   不可达 → 按源移除该 client 全部工具；可达 → 用远端最新列表替换该源工具集。
 * 全程 try-catch，绝不因自愈逻辑抛出影响原始调用返回。
 */
@Slf4j
@Component
public class McpFailureHandler {

    private final McpClientRegistry registry;
    private final AuditLogService auditLogService;

    public McpFailureHandler(McpClientRegistry registry, AuditLogService auditLogService) {
        this.registry = registry;
        this.auditLogService = auditLogService;
    }

    /**
     * 处理一次 MCP 工具调用失败。clientId 由调用边界（McpClientRegistry.callTool）传入。
     */
    public void handle(String clientId, RuntimeException ex) {
        try {
            if (isParamError(ex)) {
                // 参数错误：属调用方传参不当，不是服务器失联，不触发任何移除/同步
                log.info("MCP Client [{}] 调用失败但为参数错误，忽略自愈：{}", clientId, ex.getMessage());
                return;
            }
            McpClient client = registry.getClient(clientId);
            if (client == null) {
                log.warn("MCP Client [{}] 已不存在，跳过自愈", clientId);
                return;
            }
            boolean reachable;
            try {
                reachable = client.ping();
            } catch (Exception e) {
                reachable = false;
            }
            if (!reachable) {
                // 远端不可达：移除该源全部已注册工具并登入失败清单，等待调度器重连
                log.warn("MCP Client [{}] 调用失败且远端不可达，按源移除全部工具", clientId);
                registry.removeToolsFor(clientId);
                audit("MCP_REMOVE_SOURCE", clientId, "远端不可达，移除该源全部工具：" + ex.getMessage());
            } else {
                // 远端可达：用最新工具列表替换该源工具集（应对工具下架/变更）
                log.info("MCP Client [{}] 调用失败但远端可达，执行工具集同步", clientId);
                registry.syncTools(clientId);
                audit("MCP_SYNC_TOOLS", clientId, "远端可达，按远端列表同步该源工具集：" + ex.getMessage());
            }
        } catch (Exception e) {
            log.error("MCP 自愈处理发生异常，不影响原始调用：clientId={}", clientId, e);
        }
    }

    /** 参数错误判定：严格协议(-32602) + 消息关键词兜底 */
    private boolean isParamError(RuntimeException ex) {
        if (ex instanceof McpToolException) {
            return ((McpToolException) ex).isParamError();
        }
        if (ex.getMessage() == null) {
            return false;
        }
        String msg = ex.getMessage().toLowerCase();
        return msg.contains("argument") || msg.contains("parameter")
                || msg.contains("schema") || msg.contains("invalid params");
    }

    private void audit(String actionType, String clientId, String detail) {
        try {
            auditLogService.recordAsync(AuditLogContext.builder()
                    .actionType(actionType)
                    .targetType("mcp")
                    .targetId(clientId)
                    .detail(detail)
                    .tenantId(TenantContext.getTenantId() != null ? String.valueOf(TenantContext.getTenantId()) : null)
                    .userId(TenantContext.getUserId())
                    .build());
        } catch (Exception e) {
            log.warn("MCP 自愈审计失败，不影响处理：{}", clientId, e);
        }
    }
}