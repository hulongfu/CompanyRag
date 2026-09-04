package com.company.rag.rag.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * 单条用例的检索评测结果。
 * recallBefore: reference 中出现在任一路检索结果的比例（召回能力）
 * recallAfter:  reference 中出现在实际可用 Top-K（过滤后）的比例
 * droppedChunkIds: 检索到但被过滤掉的 reference chunk
 */
public class RetrievalEvalResult {
    private final String caseId;
    private final String query;
    private final double recallBefore;
    private final double recallAfter;
    private final List<String> droppedChunkIds;

    public RetrievalEvalResult(String caseId, String query,
                               double recallBefore, double recallAfter,
                               List<String> droppedChunkIds) {
        this.caseId = caseId;
        this.query = query;
        this.recallBefore = recallBefore;
        this.recallAfter = recallAfter;
        this.droppedChunkIds = new ArrayList<>(droppedChunkIds);
    }

    /** 被阈值/排序丢弃的正确 chunk 占比 */
    public double dropRate() {
        return recallBefore - recallAfter;
    }

    public String caseId() { return caseId; }
    public String query() { return query; }
    public double recallBefore() { return recallBefore; }
    public double recallAfter() { return recallAfter; }
    public List<String> droppedChunkIds() { return droppedChunkIds; }
}