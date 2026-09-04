package com.company.rag.rag.eval;

import com.company.rag.rag.fusion.RankNormalizer;
import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.fusion.ResultFuser;
import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.NormalizedResult;
import com.company.rag.rag.model.RagResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 免 DB 的融合层检索评测器：
 * 给定三路检索结果 + reference，量化「检索到但被过滤丢弃」问题(dropRate)。
 * 依赖既有 RankNormalizer/ResultFuser/ResultFilter，不访问数据库或 LLM。
 */
public class RetrievalEvalRunner {

    private final RankNormalizer normalizer = new RankNormalizer();
    private final ResultFuser fuser = new ResultFuser();
    private final ResultFilter filter = new ResultFilter();

    /**
     * 评估单条用例。
     * @param evalCase        测试用例
     * @param vectorResults   向量路结果（按排名序）
     * @param fulltextResults 全文路结果（按排名序）
     * @param fuzzyResults    模糊路结果（按排名序）
     * @param fusionTopK      过滤后保留条数
     * @param scoreThreshold  显式阈值，null 表示不启用硬阈值
     */
    public RetrievalEvalResult evaluate(EvalCase evalCase,
                                        List<RagResult.ChunkResult> vectorResults,
                                        List<RagResult.ChunkResult> fulltextResults,
                                        List<RagResult.ChunkResult> fuzzyResults,
                                        int fusionTopK,
                                        Double scoreThreshold) {
        Set<String> reference = new HashSet<>(evalCase.referenceChunkIds());

        // 融合前：reference 是否命中任一路
        Set<String> before = new HashSet<>();
        addIds(before, vectorResults, fulltextResults, fuzzyResults);
        int hitBefore = countHit(before, reference);
        double recallBefore = reference.isEmpty() ? 0.0 : (double) hitBefore / reference.size();

        // 执行归一化 + 融合
        List<NormalizedResult> normV = normalizer.normalize(vectorResults);
        List<NormalizedResult> normF = normalizer.normalize(fulltextResults);
        List<NormalizedResult> normZ = normalizer.normalize(fuzzyResults);
        List<FusedResult> fused = fuser.fuse(normV, normF, normZ, evalCase.query());

        // 过滤（Rerank 无法在本层模拟，因此以融合后 Top-K 作为「实际可用」口径）
        List<FusedResult> filtered = filter.filter(fused, fusionTopK, scoreThreshold);
        Set<String> after = new HashSet<>();
        filtered.forEach(f -> after.add(f.getChunkId()));

        int hitAfter = countHit(after, reference);
        double recallAfter = reference.isEmpty() ? 0.0 : (double) hitAfter / reference.size();

        // 被过滤丢弃的 reference chunk：融合前在、过滤后不在
        before.removeAll(after);
        List<String> dropped = new ArrayList<>(before);
        dropped.retainAll(reference);

        return new RetrievalEvalResult(evalCase.id(), evalCase.query(), recallBefore, recallAfter, dropped);
    }

    private void addIds(Set<String> target, List<RagResult.ChunkResult>... lists) {
        for (List<RagResult.ChunkResult> list : lists) {
            if (list == null) continue;
            list.forEach(c -> target.add(c.getChunkId()));
        }
    }

    private int countHit(Collection<String> candidates, Set<String> reference) {
        int hit = 0;
        for (String r : reference) {
            if (candidates.contains(r)) hit++;
        }
        return hit;
    }
}