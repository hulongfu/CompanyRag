package com.company.rag.document.service;

import com.company.rag.document.entity.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档解析服务
 */
public interface DocumentParseService {

    /**
     * 上传并解析文档
     */
    Document uploadAndParse(MultipartFile file, Long tenantId);

    /**
     * 解析文档内容为纯文本
     */
    String extractText(byte[] fileContent, String fileName);

    /**
     * 查询租户下所有文档列表
     */
    List<Document> listDocuments(Long tenantId);

    /**
     * 删除文档（删除向量数据 + chunk 记录 + 文档记录）
     *
     * @param id       文档 ID
     * @param tenantId 租户 ID
     */
    void deleteDocument(Long id, Long tenantId);
}
