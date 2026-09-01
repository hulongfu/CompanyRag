package com.company.rag.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 下载工具配置（简化版）
 * 
 * 配置项说明：
 * - base-dir: 文件存储根目录（可配置，支持不同环境）
 * - max-file-size: 单个文件最大大小（默认 50MB）
 * - cleanup-expire-hours: 清理标识过期时间（默认 24 小时）
 * 
 * 目录结构：
 * ${base-dir}/
 * ├── 20260831/                # 日期目录（yyyyMMdd）
 * │   ├── tenant-1/            # 租户目录
 * │   │   ├── user-100/        # 用户目录
 * │   │   │   └── api-doc.md   # 文件
 * │   │   └── user-101/
 * │   └── tenant-2/
 * └── 20260830/                # 昨天的目录（明天清理）
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "agent.download")
public class DownloadConfig {
    
    /**
     * 文件存储根目录
     * 默认：D:/downloads（Windows）或 /tmp/downloads（Linux）
     */
    private String baseDir = System.getProperty("os.name").toLowerCase().contains("win") 
        ? "D:/downloads" 
        : "/tmp/downloads";
    
    /**
     * 单个文件最大大小（字节）
     * 默认 50MB
     */
    private Long maxFileSize = 52428800L; // 50 * 1024 * 1024
    
    /**
     * 清理标识过期时间（小时）
     * 默认 24 小时（即每天清理一次）
     */
    private Integer cleanupExpireHours = 24;
}
