package com.company.rag.rag.eval;

import java.util.List;

/**
 * 单条检索评测用例
 * @param id           用例标识
 * @param query        用户问题
 * @param referenceChunkIds 人工标注的正确答案 chunkId 集合
 */
public record EvalCase(String id, String query, List<String> referenceChunkIds) {
}