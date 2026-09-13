package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.company.rag.rag.fusion.RankNormalizer;
import com.company.rag.rag.fusion.ResultFuser;
import com.company.rag.rag.model.NormalizedResult;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * 归一化 + 融合节点：合并三路检索结果并按动态权重排序。
 *
 * <p>读取状态中的三路 chunk 列表与查询参数，先归一化再融合，结果写入 FUSED 键。</p>
 */
@Slf4j
public class NormalizeFuseNode implements AsyncNodeAction {

    private final RankNormalizer normalizer;
    private final ResultFuser fuser;

    public NormalizeFuseNode(RankNormalizer normalizer, ResultFuser fuser) {
        this.normalizer = normalizer;
        this.fuser = fuser;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> out = new HashMap<>();
            // 从状态中读取查询参数，用于融合权重判断
            RagQuery query = state.<RagQuery>value(WorkflowKeys.QUERY).orElse(null);
            // 分别对三路结果做排名归一化
            List<NormalizedResult> normVector =
                    normalizer.normalize(chunks(state, WorkflowKeys.VECTOR_CHUNKS));
            List<NormalizedResult> normFullText =
                    normalizer.normalize(chunks(state, WorkflowKeys.FULLTEXT_CHUNKS));
            List<NormalizedResult> normFuzzy =
                    normalizer.normalize(chunks(state, WorkflowKeys.FUZZY_CHUNKS));
            String text = query != null ? query.getQuery() : "";
            // 融合三路结果并按最终分数排序，写入状态
            out.put(WorkflowKeys.FUSED,
                    fuser.fuse(normVector, normFullText, normFuzzy, text));
            return out;
        });
    }

    /**
     * 从状态中安全读取指定键的 chunk 列表，缺失或类型不符时降级为空列表。
     */
    @SuppressWarnings("unchecked")
    private List<RagResult.ChunkResult> chunks(OverAllState state, String key) {
        return state.<List<RagResult.ChunkResult>>value(key).orElse(Collections.emptyList());
    }
}
