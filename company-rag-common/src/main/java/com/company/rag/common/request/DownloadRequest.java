package com.company.rag.common.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 下载文件请求对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadRequest {
    
    /**
     * 文件内容
     */
    @NotBlank(message = "文件内容不能为空")
    @Size(max = 52428800, message = "文件大小不能超过 50MB") // 50MB
    private String content;
    
    /**
     * 文件名（如：report.md）
     */
    @NotBlank(message = "文件名不能为空")
    private String filename;
    
    /**
     * 文件 MIME 类型（可选，如：text/markdown）
     * 如果不指定，将根据文件扩展名自动推断
     */
    private String contentType;
}
