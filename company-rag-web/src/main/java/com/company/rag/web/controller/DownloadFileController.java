package com.company.rag.web.controller;

import com.company.rag.agent.service.DownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;

/**
 * 文件下载控制器
 * 
 * 提供文件下载接口
 */
@Slf4j
@RestController
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class DownloadFileController {
    
    private final DownloadService downloadService;
    
    /**
     * 下载文件
     * 
     * @param fileId 文件 ID
     * @return 文件内容
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<Resource> downloadFile(
        @PathVariable String fileId
    ) {
        // 1. 获取下载记录
        com.company.rag.agent.service.DownloadRecord record = downloadService.getDownloadFile(fileId);
        if (record == null) {
            log.warn("下载文件不存在或已过期：{}", fileId);
            return ResponseEntity.notFound().build();
        }
        
        // 2. 检查文件是否存在
        File file = new File(record.getFilePath());
        if (!file.exists()) {
            log.error("文件不存在：{}", record.getFilePath());
            return ResponseEntity.notFound().build();
        }
        
        // 3. 增加下载次数
        downloadService.incrementDownloadCount(fileId);
        
        // 4. 返回文件
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(record.getContentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, 
                "attachment; filename=\"" + record.getFilename() + "\"")
            .body(new FileSystemResource(file));
    }
}
