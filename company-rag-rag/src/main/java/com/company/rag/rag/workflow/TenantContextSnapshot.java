package com.company.rag.rag.workflow;

import com.company.rag.tenant.context.TenantContext;
import org.slf4j.MDC;

/**
 * 租户上下文快照 - 用于跨线程传播租户上下文与日志链路（trace）上下文。
 *
 * <p>StateGraph 的检索节点运行在图引擎内部线程池（ForkJoinPool.commonPool）的工作线程中，
 * 而 TenantContext 与日志 MDC 均基于 ThreadLocal，工作线程无法直接读取请求线程的上下文。
 * 本类在请求线程（execute 入口）捕获一次快照：包括租户信息（tenantId/tenantCode/userId/schema/sessionId）
 * 以及日志 MDC 中的 traceId/spanId（由 Micrometer TracingObservationHandler 写入）。
 * 快照随图状态传递给各节点；节点在自身线程执行前调用 {@link #apply()} 写回 TenantContext 与日志 MDC，
 * 执行完毕在 finally 中调用 {@link #clear()} 清理，避免线程池复用导致上下文串扰。</p>
 *
 * <p>快照随每次 execute 独立创建并放入图状态，因此多租户并发请求之间互不影响。</p>
 */
public final class TenantContextSnapshot {

    /** 日志 MDC 中 traceId 的键名（与 logback-spring.xml 的 %X{traceId} 一致）。 */
    private static final String MDC_TRACE_ID = "traceId";
    /** 日志 MDC 中 spanId 的键名（与 logback-spring.xml 的 %X{spanId} 一致）。 */
    private static final String MDC_SPAN_ID = "spanId";

    private final Long tenantId;
    private final String tenantCode;
    private final Long userId;
    private final String schema;
    private final String sessionId;
    private final String traceId;
    private final String spanId;

    private TenantContextSnapshot(Long tenantId, String tenantCode, Long userId,
                                  String schema, String sessionId,
                                  String traceId, String spanId) {
        this.tenantId = tenantId;
        this.tenantCode = tenantCode;
        this.userId = userId;
        this.schema = schema;
        this.sessionId = sessionId;
        this.traceId = traceId;
        this.spanId = spanId;
    }

    /**
     * 从当前线程的 TenantContext 与日志 MDC 捕获一次快照。
     *
     * @return 当前请求线程的上下文快照
     */
    public static TenantContextSnapshot captureNow() {
        return new TenantContextSnapshot(
                TenantContext.getTenantId(),
                TenantContext.getTenantCode(),
                TenantContext.getUserId(),
                TenantContext.getSchema(),
                TenantContext.getSessionId(),
                MDC.get(MDC_TRACE_ID),
                MDC.get(MDC_SPAN_ID));
    }

    /**
     * 将快照写回当前线程的 TenantContext 与日志 MDC。
     *
     * <p>仅写回非空字段，避免覆盖线程中可能存在的其他上下文。
     * MDC 的 traceId/spanId 仅当请求线程存在有效链路时才写回，未启用追踪时保持空值，
     * 以保证 worker 线程的日志链路标识与主请求链路一致。</p>
     */
    public void apply() {
        if (tenantId != null) {
            TenantContext.setTenantId(tenantId);
        }
        if (tenantCode != null) {
            TenantContext.setTenantCode(tenantCode);
        }
        if (userId != null) {
            TenantContext.setUserId(userId);
        }
        if (schema != null) {
            TenantContext.setSchema(schema);
        }
        if (sessionId != null) {
            TenantContext.setSessionId(sessionId);
        }
        // 恢复日志链路标识，使 worker 线程日志可与请求线程归入同一条 trace
        if (traceId != null) {
            MDC.put(MDC_TRACE_ID, traceId);
        }
        if (spanId != null) {
            MDC.put(MDC_SPAN_ID, spanId);
        }
    }

    /**
     * 清理当前线程的 TenantContext 与本次写回的日志 MDC 链路标识，
     * 避免线程池复用残留上下文。
     */
    public void clear() {
        TenantContext.clear();
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
    }
}
