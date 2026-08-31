package com.company.rag.agent.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 下载记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadRecord {
    
    /**
     * 文件 ID（UUID 格式）
     */
    private String fileId;
    
    /**
     * 租户 ID
     */
    private Long tenantId;
    
    /**
     * 用户 ID（可选）
     */
    private Long userId;
    
    /**
     * 文件名
     */
    private String filename;
    
    /**
     * 文件存储路径
     */
    private String filePath;
    
    /**
     * 文件 MIME 类型
     */
    private String contentType;
    
    /**
     * 文件大小（字节）
     */
    private Long size;
    
    /**
     * 过期时间
     */
    private LocalDateTime expiresAt;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 下载次数
     */
    private Integer downloadedCount;
    
    /**
     * 获取下载 URL
     */
    public String getDownloadUrl() {
        return "/api/download/" + fileId;
    }
}
