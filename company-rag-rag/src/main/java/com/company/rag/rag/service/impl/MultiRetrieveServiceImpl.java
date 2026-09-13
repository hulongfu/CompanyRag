package com.company.rag.rag.service.impl;

import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.service.MultiRetrieveService;
import com.company.rag.rag.workflow.HybridRetrievalWorkflow;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 多路检索服务实现。
 *
 * <p>自 2026-09-13 起改用 StateGraph 工作流编排（见 HybridRetrievalWorkflow），
 * 对外行为与旧流水线保持一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiRetrieveServiceImpl implements MultiRetrieveService {

    private final HybridRetrievalWorkflow workflow;

    @Override
    public List<RagResult.ChunkResult> retrieve(RagQuery query) {
        log.info("开始多路混合检索（StateGraph 工作流）| query={} | strategy={}",
                query.getQuery(), query.getRetrievalStrategy());
        return workflow.execute(query);
    }
}
