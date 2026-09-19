package com.company.rag.document.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.document.entity.Document;
import com.company.rag.document.entity.DocumentChunk;
import com.company.rag.document.mapper.DocumentChunkMapper;
import com.company.rag.document.mapper.DocumentMapper;
import com.company.rag.tenant.context.TenantSqlHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 向量化步骤（可重试、幂等）：
 * 按 documentId 读取已落库 chunk → 生成向量 → 手写 JDBC 写入 vector_store，
 * 靠 {@code vector_store.chunk_id} 部分唯一索引 + {@code ON CONFLICT} 去重，
 * 崩溃恢复重跑时不会产生重复向量。
 *
 * <p>说明：Spring AI 的 PgVectorStore 不维护 V4 新增的 chunk_id 列，无法实现幂等，
 * 故本步骤绕过 PgVectorStore，直接用 JDBC + EmbeddingModel 手工入库。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorizeStep implements DocumentPipelineStep {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "VECTORIZE";
    }

    @Override
    public void execute(PipelineTask task) {
        Document doc = documentMapper.selectById(task.getDocumentId());
        if (doc == null) {
            throw new PipelineNotFoundAfterDelete("文档已被删除，终止向量化 | taskId=" + task.getTaskId());
        }
        List<DocumentChunk> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>().eq(DocumentChunk::getDocumentId, doc.getId()));

        String vectorTable = TenantSqlHelper.getQualifiedTableName(TenantSqlHelper.requireSchema(), "vector_store");
        int inserted = 0;
        int skipped = 0;
        for (DocumentChunk chunk : chunks) {
            float[] embedding = embeddingModel.embed(chunk.getContent());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("documentId", doc.getId());
            metadata.put("tenantId", task.getTenantId());
            metadata.put("documentName", doc.getFileName() != null ? doc.getFileName() : "未知");
            metadata.put("chunkIndex", chunk.getChunkIndex());
            metadata.put("chunkId", chunk.getId());

            int rows = jdbcTemplate.update(
                    "INSERT INTO " + vectorTable + " (id, content, metadata, embedding, chunk_id) "
                            + "VALUES (?, ?, ?::jsonb, ?::vector, ?) "
                            + "ON CONFLICT (chunk_id) WHERE chunk_id IS NOT NULL DO NOTHING",
                    UUID.randomUUID(),
                    chunk.getContent(),
                    toJson(metadata),
                    toVectorString(embedding),
                    chunk.getId());
            if (rows > 0) {
                inserted++;
            } else {
                skipped++;
            }
        }

        doc.setStatus(2); // 已完成
        documentMapper.updateById(doc);
        log.info("向量化完成 | documentId={} | inserted={} | skipped={}", doc.getId(), inserted, skipped);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 metadata 失败", e);
        }
    }

    private String toVectorString(float[] embedding) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}