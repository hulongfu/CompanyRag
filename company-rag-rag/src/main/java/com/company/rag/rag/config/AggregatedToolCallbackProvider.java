package com.company.rag.rag.config;

import com.company.rag.agent.approve.ToolApprovalRequest;
import com.company.rag.agent.approve.ToolApprovalService;
import com.company.rag.agent.tool.AgentTool;
import com.company.rag.agent.tool.AgentToolRegistry;
import com.company.rag.mcp.client.McpClientRegistry;
import com.company.rag.tenant.context.TenantContextSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 聚合工具回调提供者
 * 将 AgentToolRegistry 中的所有工具 (包括 MCP 工具) 转换为 Spring AI 的 ToolCallback
 * 
 * 工作原理:
 * 1. 从 AgentToolRegistry 获取所有已注册的工具
 * 2. 将每个 AgentTool 转换为 ToolCallback
 * 3. 提供给 ChatClient 使用
 */
@Slf4j
@Component
public class AggregatedToolCallbackProvider implements ToolCallbackProvider {
    
    private final AgentToolRegistry agentToolRegistry;
    private final McpClientRegistry mcpClientRegistry;
    private final ToolApprovalService toolApprovalService;
    private final ObjectMapper objectMapper;
    
    public AggregatedToolCallbackProvider(AgentToolRegistry agentToolRegistry,
                                          McpClientRegistry mcpClientRegistry,
                                          ToolApprovalService toolApprovalService) {
        this.agentToolRegistry = agentToolRegistry;
        this.mcpClientRegistry = mcpClientRegistry;
        this.toolApprovalService = toolApprovalService;
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public ToolCallback[] getToolCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();
        
        // 1. 添加 AgentToolRegistry 中的所有工具
        List<Map<String, Object>> tools = agentToolRegistry.listTools();
        log.debug("从 AgentToolRegistry 获取工具：{}", tools.size());
        
        for (Map<String, Object> toolInfo : tools) {
            String toolName = (String) toolInfo.get("name");
            String description = (String) toolInfo.get("description");
            
            try {
                AgentTool agentTool = agentToolRegistry.getTool(toolName);
                if (agentTool != null) {
                    ToolCallback callback = createToolCallback(agentTool, toolName, description);
                    callbacks.add(callback);
                    log.debug("添加工具回调：{}", toolName);
                } else {
                    log.warn("工具 {} 在 AgentToolRegistry 中不存在", toolName);
                }
            } catch (Exception e) {
                log.error("创建工具回调失败：{}", toolName, e);
            }
        }
        
        log.info("聚合工具回调提供者：共 {} 个工具", callbacks.size());
        return callbacks.toArray(new ToolCallback[0]);
    }
    
    /**
     * 创建 ToolCallback
     */
    private ToolCallback createToolCallback(AgentTool agentTool, String name, String description) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                // 构建工具定义
                // inputSchema 需要是 JSON 字符串格式
                String inputSchemaJson;
                try {
                    inputSchemaJson = objectMapper.writeValueAsString(agentTool.getParameterSchema());
                } catch (Exception e) {
                    log.warn("转换 inputSchema 失败，使用默认 schema", e);
                    inputSchemaJson = "{\"type\":\"object\",\"properties\":{}}";
                }
                
                return ToolDefinition.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(inputSchemaJson)
                        .build();
            }
            
            @Override
            public String call(String input) {
                return doCall(input, null);
            }

            /**
             * graph 工具节点跑在框架自己的调度线程（boundedElastic-*）上，
             * {@code TenantContext} 这类普通 ThreadLocal 无法跟随过去，导致检索报
             * “未设置租户上下文”、审计落库缺 tenant_id。
             * 框架会把 {@code RunnableConfig} 的 metadata 原样放进 ToolContext，
             * 因此约定用 metadata 携带租户快照，在这里写回当前执行线程。
             */
            @Override
            public String call(String input, ToolContext toolContext) {
                return doCall(input, toolContext);
            }

            private String doCall(String input, ToolContext toolContext) {
                TenantContextSnapshot snapshot = tenantSnapshotOf(toolContext);
                if (snapshot == null) {
                    return invokeTool(input);
                }
                // 工具可能与调用线程同线程执行，直接 clear() 会把调用线程后续的
                // DB 访问打成无租户状态，因此进入前捕获原值、退出时精确还原
                TenantContextSnapshot previous = TenantContextSnapshot.captureNow();
                snapshot.apply();
                try {
                    return invokeTool(input);
                } finally {
                    previous.clear();
                    previous.apply();
                }
            }

            private TenantContextSnapshot tenantSnapshotOf(ToolContext toolContext) {
                if (toolContext == null || toolContext.getContext() == null) {
                    return null;
                }
                Object value = toolContext.getContext().get(TenantContextSnapshot.METADATA_KEY);
                return value instanceof TenantContextSnapshot tenantSnapshot ? tenantSnapshot : null;
            }

            private String invokeTool(String input) {
                try {
                    // Spring AI 传递的是 JSON 字符串参数，需要解析为 Map
                    log.debug("调用工具：{}, input={}", name, input);
                    
                    // 解析 JSON 参数
                    Map<String, Object> params;
                    if (input == null || input.trim().isEmpty() || "null".equals(input)) {
                        params = Map.of();
                    } else {
                        try {
                            params = objectMapper.readValue(input, Map.class);
                        } catch (Exception e) {
                            log.warn("解析输入参数失败，使用空参数：{}", input, e);
                            params = Map.of();
                        }
                    }
                    
                    // 审批门（方案 A 同步等待）：命中则落 PENDING 单并阻塞等待人工裁决
                    if (toolApprovalService.needsApproval(name, agentTool)) {
                        ToolApprovalRequest req = toolApprovalService.createRequest(name, params);
                        ToolApprovalService.ApprovalVerdict verdict =
                                toolApprovalService.await(req.getId(), name);
                        if (!verdict.shouldProceed()) {
                            String denyMsg = verdict.getDenyMessage() != null
                                    ? verdict.getDenyMessage() : "审批未通过";
                            log.info("[APPROVAL] 工具 {} 被拦截（未通过审批）：{}", name, denyMsg);
                            return "工具调用被审批门拦截，未执行。原因：" + denyMsg;
                        }
                        log.info("[APPROVAL] 工具 {} 审批通过，继续执行", name);
                    }

                    // 调用 AgentTool
                    String result = agentTool.execute(params);
                    log.debug("工具 {} 调用完成，resultLength={}", name, 
                             result != null ? result.length() : 0);
                    return result;
                } catch (Exception e) {
                    log.error("工具调用失败：{}", name, e);
                    return "工具调用失败：" + e.getMessage();
                }
            }
        };
    }
}
