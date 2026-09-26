package com.company.rag.rag.service.support;

import com.company.rag.rag.model.RagResult;

import java.util.stream.Collectors;

/**
 * 从 RAG 检索结果重建 prompt/落库使用的 context（search 与 ragSearch 共用，避免两处漂移）。
 */
public final class RagResultContextBuilder {

    private RagResultContextBuilder() {
    }

    /**
     * 按现状规则重建 context：逐块 "[来源:{documentName}] {content}"，块间 "\n\n"。
     *
     * <p>RagResult 无 context 字段（RagResult.java:11-16），故须从 chunks 重建；
     * 单块 documentName 为 null 时回落 "未知"。避免 rag_session.context 静默为空。</p>
     *
     * @param result 检索结果；null 或 chunks 为空时返回空串
     */
    public static String build(RagResult result) {
        if (result == null || result.getChunks() == null || result.getChunks().isEmpty()) {
            return "";
        }
        return result.getChunks().stream()
                .map(c -> {
                    String name = c.getDocumentName() != null ? c.getDocumentName() : "未知";
                    return "[来源:" + name + "] " + c.getContent();
                })
                .collect(Collectors.joining("\n\n"));
    }
}