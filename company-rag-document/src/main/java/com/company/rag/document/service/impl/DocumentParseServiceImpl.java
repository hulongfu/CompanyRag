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
import java.util.UUID;

/**
 * 文档处理服务完整实现
 * 
 * 完整流程：
 * 1. 文档上传 → 保存文档元数据到数据库
 * 2. 文本提取 → Apache Tika 解析
 * 3. 智能切分 → 语义切分策略
 * 4. 向量化 → Embedding 模型
 * 5. 向量存储 → PGVector
 * 6. 切分块元数据保存 → doc_chunk 表
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
            log.info("0. 先保存文档记录，获取自增 ID");
            documentMapper.insert(doc);

            log.info("1. 提取文本");
            byte[] content = file.getBytes();
            String text = extractText(content, originalName);

            log.info("2. 智能切分");
            List<DocumentChunk> chunks = splitDocument(text, doc, SplitStrategy.SEMANTIC_CHUNK);
            doc.setChunkCount(chunks.size());
            doc.setStatus(1); // 已切分

            log.info("3. 保存切分块到数据库");
            for (DocumentChunk chunk : chunks) {
                chunkMapper.insert(chunk);
            }

            log.info("4. 向量化并存储到 PGVector");
            vectorizeAndStore(chunks, doc.getId(), doc.getFileName(), tenantId);
            doc.setStatus(2); // 已向量化

            log.info("5. 更新文档状态");
            documentMapper.updateById(doc);

            log.info("文档处理完成 | documentId={} | chunks={}", doc.getId(), chunks.size());

        } catch (Exception e) {
            log.error("文档处理失败：{}", e.getMessage(), e);
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
            // Tika 自动检测文件类型并提取文本
            String text = tika.parseToString(new java.io.ByteArrayInputStream(fileContent));
            
            // 诊断日志：记录提取的文本长度
            log.info("文本提取完成 | 文件名={} | 原始文件大小={} bytes | 提取文本长度={} characters", 
                    fileName, fileContent.length, text.length());
            
            // 对于 TXT 文件，检查是否可能截断
            if (fileName != null && fileName.toLowerCase().endsWith(".txt")) {
                // 计算提取率（文本长度/文件大小），TXT 文件通常提取率应该在 80% 以上
                // 因为 TXT 是纯文本，提取后长度应该接近原始大小（考虑编码转换）
                double extractRate = fileContent.length > 0 ? (double) text.length() / fileContent.length : 0;
                
                // 如果提取率过低，可能是编码问题导致截断
                if (extractRate < 0.8) {
                    log.warn("检测到 TXT 文件可能未完全提取 | 文件大小={} bytes | 文本长度={} characters | 提取率={:.2%} | 尝试使用 UTF-8 直接读取", 
                            fileContent.length, text.length(), extractRate);
                    
                    // 尝试使用 UTF-8 直接读取
                    try {
                        String utf8Text = new String(fileContent, java.nio.charset.StandardCharsets.UTF_8);
                        if (utf8Text.length() > text.length()) {
                            log.info("UTF-8 直接读取成功 | 长度={} characters | 提取率={:.2%}，使用 UTF-8 结果", 
                                    utf8Text.length(), (double) utf8Text.length() / fileContent.length);
                            return utf8Text;
                        } else {
                            log.info("UTF-8 直接读取未改善结果 | UTF-8 长度={} characters", utf8Text.length());
                        }
                    } catch (Exception e) {
                        log.warn("UTF-8 直接读取失败，使用 Tika 结果", e);
                    }
                }
            }
            
            return text;
        } catch (TikaException | IOException e) {
            log.error("文档解析失败：{}", fileName, e);
            throw new RuntimeException("文档解析失败：" + e.getMessage());
        }
    }

    @Override
    public List<Document> listDocuments(Long tenantId) {
        return documentMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Document>()
                .eq("tenant_id", tenantId)
                .orderByDesc("create_time")
        );
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
        int chunkSize = 512; // 默认 chunk 大小
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
     * 向量化并存储到 PGVector
     * 
     * 注意：Spring AI 的 PgVectorStore 要求 Document ID 必须是 UUID 格式
     * chunk.getId() 是数据库自增 ID，仅用于数据库内部关联查询
     * 向量化时使用 UUID.randomUUID() 生成独立的向量存储 ID
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

            // 创建 Spring AI Document
            // 注意：必须使用 UUID 格式，因为 Spring AI 的 PgVectorStore 要求 ID 符合 UUID 规范
            // chunk.getId() 是数据库自增 ID，仅用于数据库内部关联，不直接用于向量存储 ID
            org.springframework.ai.document.Document aiDoc = new org.springframework.ai.document.Document(
                UUID.randomUUID().toString(),  // 生成 UUID 作为向量存储 ID
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
