package com.company.rag.document.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.document.entity.DocumentChunk;
import com.company.rag.document.mapper.DocumentChunkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 入库步骤（可重试）：
 * 幂等校验 doc_chunk 已就绪。ChunkStep 负责实际写入，本步骤作为重放保障——
 * 若崩溃发生在 ChunkStep 提交后、状态推进前，重入时此处确认 chunk 完整，
 * 缺失则抛异常（可重试），避免后续向量化读到空数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestStep implements DocumentPipelineStep {

    private final DocumentChunkMapper chunkMapper;

    @Override
    public String name() {
        return "INGEST";
    }

    @Override
    public void execute(PipelineTask task) {
        Long count = chunkMapper.selectCount(
                new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, task.getDocumentId()));
        if (count == null || count == 0) {
            // 依赖 ChunkStep 幂等补写；此处失败可被重试兜底
            throw new IllegalStateException("chunk 数据缺失，等待切分步骤重放 | documentId=" + task.getDocumentId());
        }
        log.debug("入库步骤确认 chunk 就绪 | documentId={} | chunks={}", task.getDocumentId(), count);
    }
}