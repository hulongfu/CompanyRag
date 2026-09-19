package com.company.rag.document.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.document.entity.Document;
import com.company.rag.document.entity.DocumentChunk;
import com.company.rag.document.mapper.DocumentChunkMapper;
import com.company.rag.document.mapper.DocumentMapper;
import com.company.rag.document.service.DocumentParseService;
import com.company.rag.document.splitter.DocumentSplitter;
import com.company.rag.document.splitter.SplitStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 切分步骤（可重试）：
 * 读取临时文件 → Tika 提取文本 → 语义切分 → 幂等写 doc_chunk → 更新 rag_document 状态。
 *
 * <p>幂等：先按 documentId 查询，若已存在 chunk 则跳过（崩溃恢复续跑的重入保护）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChunkStep implements DocumentPipelineStep {

    private static final int CHUNK_SIZE = 512;
    private static final int CHUNK_OVERLAP = 64;

    private final DocumentParseService documentParseService;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final List<DocumentSplitter> splitters;

    @Override
    public String name() {
        return "CHUNK";
    }

    @Override
    public void execute(PipelineTask task) {
        byte[] content = readFileContent(task.getFileRef());
        Document doc = documentMapper.selectById(task.getDocumentId());
        if (doc == null) {
            throw new PipelineNotFoundAfterDelete("文档已被删除，终止切分 | taskId=" + task.getTaskId());
        }

        String text = documentParseService.extractText(content, task.getFileName());
        List<DocumentChunk> chunks = split(text, doc);

        // 幂等：若该文档已生成 chunk（例如崩溃恢复后重跑），直接跳过重复写入
        Long existing = chunkMapper.selectCount(
                new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, doc.getId()));
        if (existing != null && existing > 0) {
            log.info("切分步骤检测到已存在 chunk，跳过重复写入 | documentId={} | existing={}", doc.getId(), existing);
            return;
        }

        for (DocumentChunk chunk : chunks) {
            chunkMapper.insert(chunk);
        }
        doc.setChunkCount(chunks.size());
        doc.setStatus(1); // 处理中（已切分）
        documentMapper.updateById(doc);
        log.info("切分完成并落库 | documentId={} | chunks={}", doc.getId(), chunks.size());
    }

    private List<DocumentChunk> split(String text, Document doc) {
        DocumentSplitter splitter = splitters.stream()
                .filter(s -> s.getStrategy() == SplitStrategy.SEMANTIC_CHUNK)
                .findFirst()
                .orElse(splitters.get(0));
        List<DocumentChunk> chunks = splitter.split(text, CHUNK_SIZE, CHUNK_OVERLAP);
        for (DocumentChunk chunk : chunks) {
            chunk.setDocumentId(doc.getId());
            chunk.setTenantId(doc.getTenantId());
        }
        return chunks;
    }

    private byte[] readFileContent(String fileRef) {
        try {
            return Files.readAllBytes(Path.of(fileRef));
        } catch (IOException e) {
            throw new IllegalStateException("读取临时文件失败：" + fileRef, e);
        }
    }
}