package com.company.rag.document.service.impl;

import com.company.rag.document.entity.Document;
import com.company.rag.document.entity.DocumentChunk;
import com.company.rag.document.mapper.DocumentChunkMapper;
import com.company.rag.document.mapper.DocumentMapper;
import com.company.rag.document.service.DocumentParseService;
import com.company.rag.document.splitter.DocumentSplitter;
import com.company.rag.document.splitter.SplitStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档处理服务完整实现
 * 
 * 完整流程：
 * 1. 文档上传 → 保存文档元数据到数据库
 * 2. 文本提取 → Apache Tika解析
 * 3. 智能切分 → 语义切分策略
 * 4. 向量化 → Embedding模型
 * 5. 向量存储 → PGVector
 * 6. 切分块元数据保存 → doc_chunk表
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParseServiceImpl implements DocumentParseService {

    private static final Tika tika = new Tika();
    private final VectorStore vectorStore;
    private final List<DocumentSplitter> splitters;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Document uploadAndParse(MultipartFile file, Long tenantId) {
        Document doc = new Document();
        doc.setTenantId(tenantId);
        doc.setFileName(file.getOriginalFilename());
        doc.setFileSize(file.getSize());
        doc.setStatus(0); // 待处理

        String originalName = file.getOriginalFilename();
        if (originalName != null && originalName.contains(".")) {
            doc.setFileType(originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase());
        }

        try {
            // 0. 先保存文档记录，获取自增ID
            documentMapper.insert(doc);
            
            // 1. 提取文本
            byte[] content = file.getBytes();
            String text = extractText(content, originalName);

            // 2. 智能切分
            List<DocumentChunk> chunks = splitDocument(text, doc, SplitStrategy.SEMANTIC_CHUNK);
            doc.setChunkCount(chunks.size());
            doc.setStatus(1); // 已切分

            // 3. 保存切分块到数据库
            for (DocumentChunk chunk : chunks) {
                chunkMapper.insert(chunk);
            }

            // 4. 向量化并存储到PGVector
            vectorizeAndStore(chunks, doc.getId(), doc.getFileName(), tenantId);
            doc.setStatus(2); // 已向量化

            // 5. 更新文档状态
            documentMapper.updateById(doc);

            log.info("文档处理完成 | documentId={} | chunks={}", doc.getId(), chunks.size());

        } catch (Exception e) {
            log.error("文档处理失败: {}", e.getMessage(), e);
            doc.setStatus(-1); // 失败
            doc.setErrorMsg(e.getMessage());
            // 尝试更新失败状态
            try { documentMapper.updateById(doc); } catch (Exception ignored) {}
        }

        return doc;
    }

    @Override
    public String extractText(byte[] fileContent, String fileName) {
        try {
            // Tika自动检测文件类型并提取文本
            return tika.parseToString(new java.io.ByteArrayInputStream(fileContent));
        } catch (TikaException | IOException e) {
            log.error("文档解析失败: {}", fileName, e);
            throw new RuntimeException("文档解析失败: " + e.getMessage());
        }
    }

    /**
     * 文档切分
     */
    private List<DocumentChunk> splitDocument(String text, Document doc, SplitStrategy strategy) {
        // 选择切分策略
        DocumentSplitter splitter = splitters.stream()
                .filter(s -> s.getStrategy() == strategy)
                .findFirst()
                .orElse(splitters.get(0)); // 默认使用第一个策略

        // 执行切分
        int chunkSize = 512; // 默认chunk大小
        int overlap = 64; // 默认重叠大小
        List<DocumentChunk> chunks = splitter.split(text, chunkSize, overlap);

        // 设置文档关联
        for (DocumentChunk chunk : chunks) {
            chunk.setDocumentId(doc.getId());
            chunk.setTenantId(doc.getTenantId());
        }

        return chunks;
    }

    /**
     * 向量化并存储到PGVector
     */
    private void vectorizeAndStore(List<DocumentChunk> chunks, Long documentId, String documentName, Long tenantId) {
        List<org.springframework.ai.document.Document> aiDocuments = new ArrayList<>();

        for (DocumentChunk chunk : chunks) {
            // 构建元数据，包含文档名称便于检索时展示来源
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("documentId", documentId);
            metadata.put("tenantId", tenantId);
            metadata.put("documentName", documentName != null ? documentName : "未知");
            metadata.put("chunkIndex", chunk.getChunkIndex());
            metadata.put("chunkId", chunk.getId());

            // 创建Spring AI Document
            org.springframework.ai.document.Document aiDoc = new org.springframework.ai.document.Document(
                chunk.getId().toString(),
                chunk.getContent(),
                metadata
            );
            aiDocuments.add(aiDoc);
        }

        // 批量向量化并存储
        vectorStore.add(aiDocuments);
        log.info("向量化存储完成 | documentId={} | count={}", documentId, aiDocuments.size());
    }
}
