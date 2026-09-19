package com.company.rag.agent.approve;

/**
 * 审批单状态机：PENDING → EXECUTED / DENIED。
 * 仅 PENDING 可被人工决策（幂等），超时由收敛器或同步等待线程自动转 DENIED。
 */
public final class ToolApprovalStatus {

    private ToolApprovalStatus() {
    }

    /** 待审批：审批单落库的初始状态，等待人工决策。 */
    public static final String PENDING = "PENDING";

    /** 已批准：明文 approve 后置此状态，等待中的线程被放行执行工具并回填结果。 */
    public static final String EXECUTED = "EXECUTED";

    /** 已拒绝：明文 deny 或超时自动拒绝，等待中的线程收到拒绝文案。 */
    public static final String DENIED = "DENIED";
}