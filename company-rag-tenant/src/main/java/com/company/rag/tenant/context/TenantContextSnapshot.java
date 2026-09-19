package com.company.rag.tenant.context;

import com.company.rag.tenant.context.TenantContext;
import org.slf4j.MDC;

/**
 * 租户上下文快照 - 用于跨线程传播租户上下文与日志链路（trace）上下文。
 *
 * <p>TenantContext 与日志 MDC 均基于 ThreadLocal，工作线程无法直接读取请求线程的上下文。
 * 通过本类在请求线程捕获一次快照，随后在 worker 线程执行前调用 {@link #apply()} 写回，
 * finally 中调用 {@link #clear()} 清理，避免线程池复用导致上下文串扰。</p>
 *
 * <p>本类自 company-rag-rag 的 workflow 包下沉而来，供 rag 与 document 模块共用，
 * 解决 document pipeline 需要跨线程恢复租户上下文却不允许反向依赖 rag 的问题。</p>
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
     * 手动构造快照（供崩溃补偿等无请求上下文的线程使用）。
     * 仅需 schema 与 tenantId，其余字段为空由 {@link #apply()} 跳过。
     *
     * @param tenantId 租户 ID
     * @param schema   租户 schema 名
     * @return 快照
     */
    public static TenantContextSnapshot of(Long tenantId, String schema) {
        return new TenantContextSnapshot(tenantId, null, null, schema, null, null, null);
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

    /** @return 租户 ID（供 fail-closed 断言读取 expected 值） */
    public Long getTenantId() {
        return tenantId;
    }

    /** @return 租户 schema 名（供 fail-closed 断言读取 expected 值） */
    public String getSchema() {
        return schema;
    }
}