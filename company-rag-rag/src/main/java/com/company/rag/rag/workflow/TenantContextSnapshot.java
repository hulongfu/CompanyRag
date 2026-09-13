package com.company.rag.rag.workflow;

import com.company.rag.tenant.context.TenantContext;

/**
 * 租户上下文快照 - 用于跨线程传播租户上下文。
 *
 * <p>StateGraph 的检索节点运行在 ForkJoinPool 工作线程中，而 TenantContext 基于 ThreadLocal，
 * 工作线程无法直接读取请求线程的租户信息。本类在请求线程（execute 入口）捕获一次上下文快照，
 * 随图状态传递给各节点；节点在自身线程执行前调用 {@link #apply()} 写回 TenantContext，
 * 执行完毕在 finally 中调用 {@link #clear()} 清理，避免线程池复用导致上下文串扰。</p>
 *
 * <p>快照随每次 execute 独立创建并放入图状态，因此多租户并发请求之间互不影响。</p>
 */
public final class TenantContextSnapshot {

    private final Long tenantId;
    private final String tenantCode;
    private final Long userId;
    private final String schema;
    private final String sessionId;

    private TenantContextSnapshot(Long tenantId, String tenantCode, Long userId,
                                  String schema, String sessionId) {
        this.tenantId = tenantId;
        this.tenantCode = tenantCode;
        this.userId = userId;
        this.schema = schema;
        this.sessionId = sessionId;
    }

    /**
     * 从当前线程的 TenantContext 捕获一次快照。
     *
     * @return 当前租户上下文快照
     */
    public static TenantContextSnapshot captureNow() {
        return new TenantContextSnapshot(
                TenantContext.getTenantId(),
                TenantContext.getTenantCode(),
                TenantContext.getUserId(),
                TenantContext.getSchema(),
                TenantContext.getSessionId());
    }

    /**
     * 将快照写回当前线程的 TenantContext。
     *
     * <p>仅写回非空字段，避免覆盖线程中可能存在的其他上下文。</p>
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
    }

    /**
     * 清理当前线程的 TenantContext，避免线程池复用残留上下文。
     */
    public void clear() {
        TenantContext.clear();
    }
}
