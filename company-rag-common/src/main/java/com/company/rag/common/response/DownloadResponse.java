package com.company.rag.common.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 下载文件响应对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadResponse {
    
    /**
     * 文件 ID（UUID 格式）
     */
    private String fileId;
    
    /**
     * 文件名
     */
    private String filename;
    
    /**
     * 文件 MIME 类型
     */
    private String contentType;
    
    /**
     * 文件大小（字节）
     */
    private Long size;
    
    /**
     * 下载链接
     */
    private String downloadUrl;
    
    /**
     * 过期时间
     */
    private String expiresAt;
    
    /**
     * 创建时间
     */
    private String createdAt;
    
    /**
     * 下载次数
     */
    private Integer downloadedCount;
}
