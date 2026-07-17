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
}
