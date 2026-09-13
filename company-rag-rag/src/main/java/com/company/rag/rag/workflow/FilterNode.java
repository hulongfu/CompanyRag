package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.model.FusedResult;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * 最终筛选节点：阈值过滤 + 按分排序取 Top-K，产出 ChunkResult 列表。
 *
 * <p>读取状态中的融合结果与查询参数，调用 ResultFilter.filter 复现原流水线筛选语义，
 * 再借助 FusedResult 到 RagResult.ChunkResult 的继承链宽化输出类型写入 FILTERED 键。</p>
 */
@Slf4j
public class FilterNode implements AsyncNodeAction {

    private final ResultFilter filter;

    public FilterNode(ResultFilter filter) {
        this.filter = filter;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> out = new HashMap<>();
            // 从状态中读取查询参数与融合结果
            RagQuery query = state.<RagQuery>value(WorkflowKeys.QUERY).orElse(null);
            List<FusedResult> fused =
                    state.<List<FusedResult>>value(WorkflowKeys.FUSED)
                            .orElse(Collections.emptyList());
            int fusionTopK = query != null && query.getFusionTopK() != null
                    ? query.getFusionTopK() : 30;
            Double scoreThreshold = query != null ? query.getScoreThreshold() : null;
            // 与原流水线一致：先 filter（阈值+topK），再依继承链宽化返回类型
            List<FusedResult> filtered = filter.filter(fused, fusionTopK, scoreThreshold);
            List<RagResult.ChunkResult> output = new ArrayList<>(filtered);
            out.put(WorkflowKeys.FILTERED, output);
            return out;
        });
    }
}
