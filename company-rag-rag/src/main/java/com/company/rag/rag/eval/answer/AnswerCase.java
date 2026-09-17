package com.company.rag.rag.eval.answer;

/**
 * 待评估的一条问答样本。
 * tenantId: 显式租户 ID，跨线程落库不依赖 ThreadLocal（在线取 verifiedTenantId / 手动取 X-Tenant-Id）
 * sessionRowId: 关联 rag_session.id（在线评估来源定位，可空）
 * source: 评估来源（online / manual，默认 manual）
 */
public record AnswerCase(String query, String context, String answer,
                         Long tenantId, Long sessionRowId, String source) {
    public AnswerCase(String query, String context, String answer) {
        this(query, context, answer, null, null, "manual");
    }
}
