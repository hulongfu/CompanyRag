package com.company.rag.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 下载工具配置
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "download")
public class DownloadConfig {
    
    /**
     * 文件保留时间（小时）
     * 默认 24 小时
     */
    private Integer retentionHours = 24;
    
    /**
     * 临时文件存储目录
     * 默认：系统临时目录下的 company-rag-downloads 子目录
     */
    private String tempDir = System.getProperty("java.io.tmpdir") + "/company-rag-downloads";
    
    /**
     * 单个文件最大大小（字节）
     * 默认 50MB
     */
    private Long maxFileSize = 52428800L; // 50 * 1024 * 1024
}
