package com.company.rag.document.service;

import com.company.rag.document.entity.Document;
import org.springframework.web.multipart.MultipartFile;

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
}
