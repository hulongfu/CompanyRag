package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.retriever.impl.FuzzyRetriever;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * 模糊检索节点：执行单路模糊检索并写入图状态。
 *
 * <p>读取状态中的查询参数，调用 FuzzyRetriever.retrieve 获取结果并写入 KEY_FUZZY 键。
 * 当单路检索失败时，按原流水线的容错语义降级为空列表而非向上抛异常。</p>
 */
@Slf4j
public class FuzzyRetrieveNode implements AsyncNodeAction {

    /** 模糊检索的固定 topK，与原 MultiRetrieveServiceImpl 保持一致。 */
    private static final int TOP_K = 30;

    private final FuzzyRetriever retriever;

    public FuzzyRetrieveNode(FuzzyRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> out = new HashMap<>();
            // 节点运行在 ForkJoinPool 工作线程，须将请求线程的租户上下文快照写回当前线程
            TenantContextSnapshot snapshot =
                    state.<TenantContextSnapshot>value(WorkflowKeys.TENANT_CONTEXT).orElse(null);
            if (snapshot != null) {
                snapshot.apply();
            }
            try {
                // 从状态中读取查询参数，并取出查询文本
                RagQuery query = state.<RagQuery>value(WorkflowKeys.QUERY).orElse(null);
                String text = query != null ? query.getQuery() : "";
                List<RagResult.ChunkResult> chunks = retriever.retrieve(text, TOP_K);
                out.put(WorkflowKeys.FUZZY_CHUNKS, chunks);
            } catch (Exception e) {
                // 单路检索失败时降级为空列表，保持对外行为稳定
                log.warn("模糊检索节点失败，降级为空结果 | error={}", e.getMessage());
                out.put(WorkflowKeys.FUZZY_CHUNKS, Collections.emptyList());
            } finally {
                // 清理当前线程租户上下文，避免线程池复用产生串扰
                if (snapshot != null) {
                    snapshot.clear();
                }
            }
            return out;
        });
    }
}