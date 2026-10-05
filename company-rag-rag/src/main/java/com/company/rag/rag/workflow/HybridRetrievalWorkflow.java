package com.company.rag.rag.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.company.rag.rag.fusion.RankNormalizer;
import com.company.rag.rag.fusion.ResultFilter;
import com.company.rag.rag.fusion.ResultFuser;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.retriever.impl.FullTextRetriever;
import com.company.rag.rag.retriever.impl.FuzzyRetriever;
import com.company.rag.rag.retriever.impl.VectorRetriever;
import com.company.rag.tenant.context.TenantContextSnapshot;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 混合检索工作流。
 *
 * <p>将三路检索（向量/全文/模糊）作为并行节点、归一化融合与最终筛选作为串行节点组装成 StateGraph。
 * 对外行为与原 MultiRetrieveServiceImpl 保持一致，仅替换编排方式。</p>
 */
@Slf4j
@Component
public class HybridRetrievalWorkflow {

    private final CompiledGraph graph;

    public HybridRetrievalWorkflow(
            VectorRetriever vectorRetriever,
            FullTextRetriever fullTextRetriever,
            FuzzyRetriever fuzzyRetriever,
            RankNormalizer normalizer,
            ResultFuser fuser,
            ResultFilter filter) {
        try {
            StateGraph stateGraph = new StateGraph();
            stateGraph.addNode("vectorRetrieval", new VectorRetrieveNode(vectorRetriever));
            stateGraph.addNode("fullTextRetrieval", new FullTextRetrieveNode(fullTextRetriever));
            stateGraph.addNode("fuzzyRetrieval", new FuzzyRetrieveNode(fuzzyRetriever));
            stateGraph.addNode("normalizeAndFuse", new NormalizeFuseNode(normalizer, fuser));
            stateGraph.addNode("finalFilter", new FilterNode(filter));

            // 三路检索并行（无相互依赖），随后串行融合与筛选
            stateGraph.addEdge(StateGraph.START, "vectorRetrieval");
            stateGraph.addEdge(StateGraph.START, "fullTextRetrieval");
            stateGraph.addEdge(StateGraph.START, "fuzzyRetrieval");
            stateGraph.addEdge("vectorRetrieval", "normalizeAndFuse");
            stateGraph.addEdge("fullTextRetrieval", "normalizeAndFuse");
            stateGraph.addEdge("fuzzyRetrieval", "normalizeAndFuse");
            stateGraph.addEdge("normalizeAndFuse", "finalFilter");
            stateGraph.addEdge("finalFilter", StateGraph.END);

            this.graph = stateGraph.compile();
        } catch (Exception e) {
            throw new IllegalStateException("初始化混合检索工作流失败", e);
        }
    }

    /**
     * 执行混合检索工作流。
     *
     * @param query 用户查询
     * @return 最终筛选后的段落列表（与旧 MultiRetrieveServiceImpl 行为一致）
     */
    @SuppressWarnings("unchecked")
    public List<RagResult.ChunkResult> execute(RagQuery query) {
        Map<String, Object> init = new HashMap<>();
        init.put(WorkflowKeys.QUERY, query);
        // 在请求线程捕获租户上下文快照，随图状态传递给工作线程执行的三路检索节点
        init.put(WorkflowKeys.TENANT_CONTEXT, TenantContextSnapshot.captureNow());
        init.put(WorkflowKeys.VECTOR_CHUNKS, Collections.<RagResult.ChunkResult>emptyList());
        init.put(WorkflowKeys.FULLTEXT_CHUNKS, Collections.<RagResult.ChunkResult>emptyList());
        init.put(WorkflowKeys.FUZZY_CHUNKS, Collections.<RagResult.ChunkResult>emptyList());
        init.put(WorkflowKeys.FUSED, Collections.emptyList());
        init.put(WorkflowKeys.FILTERED, Collections.<RagResult.ChunkResult>emptyList());

        Optional<OverAllState> finalState = graph.invoke(init);
        if (finalState.isPresent()) {
            Optional<List<RagResult.ChunkResult>> filtered =
                    finalState.get().<List<RagResult.ChunkResult>>value(WorkflowKeys.FILTERED);
            if (filtered.isPresent()) {
                return filtered.get();
            }
        }
        return Collections.emptyList();
    }
}
