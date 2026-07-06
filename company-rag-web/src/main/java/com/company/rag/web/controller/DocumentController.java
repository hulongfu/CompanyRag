package com.company.rag.web.controller;

import com.company.rag.common.model.R;
import com.company.rag.document.entity.Document;
import com.company.rag.document.service.DocumentParseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档管理接口
 */
@RestController
@RequestMapping("/api/document")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentParseService documentParseService;

    @PostMapping("/upload")
    public R<Document> upload(@RequestParam("file") MultipartFile file,
                              @RequestParam(value = "tenantId", defaultValue = "1") Long tenantId) {
        Document doc = documentParseService.uploadAndParse(file, tenantId);
        return R.ok(doc);
    }
}
