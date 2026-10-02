package com.company.rag.agent.stream;

import com.company.rag.tenant.context.TenantContext;
import io.micrometer.context.ContextSnapshot;

/**
 * 流式任务的上下文快照：在**请求线程**捕获，在**池任务线程**恢复。
 *
 * <p>为什么需要它：{@code TenantContext} 与 {@code ToolCallRecorder} 都是 ThreadLocal，
 * 流式任务跑在独立线程池里，不显式传递就会丢租户上下文（跨租户风险）与工具调用记录。
 * 语义与 {@code RagAgentService#callAgentWithTimeout} 里的手动快照完全一致：
 * {@link ContextSnapshot} 负责 Observation span + MDC，五个租户字段是自定义 ThreadLocal，
 * ContextSnapshot 捕获不到，必须逐字段搬运。
 *
 * <p>只在 agent 模块内使用，不下沉到 common。
 */
public record TenantStreamContext(ContextSnapshot snapshot,
                                  String schema,
                                  Long tenantId,
                                  Long userId,
                                  String tenantCode,
                                  String sessionId) {

    /**
     * 在请求线程捕获当前线程的全部上下文。
     */
    public static TenantStreamContext captureNow() {
        return new TenantStreamContext(
                ContextSnapshot.captureAll(),
                TenantContext.getSchema(),
                TenantContext.getTenantId(),
                TenantContext.getUserId(),
                TenantContext.getTenantCode(),
                TenantContext.getSessionId());
    }
}
