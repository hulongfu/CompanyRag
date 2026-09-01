package com.company.rag.web.controller;

import com.company.rag.agent.service.DownloadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.File;
import java.nio.file.Path;

/**
 * 文件下载控制器
 * 
 * 提供文件下载接口，并在下载时触发异步清理过期目录
 * 
 * 目录结构：
 * ${agent.download.base-dir}/
 * ├── 20260831/                # 日期目录（yyyyMMdd）
 * │   ├── tenant-1/            # 租户目录
 * │   │   ├── session-xxx/     # 会话目录
 * │   │   │   └── api-doc.md   # 文件
 * │   │   └── session-yyy/
 * │   └── tenant-2/
 * └── 20260830/                # 昨天的目录（明天清理）
 */
@Slf4j
@Controller
@RequestMapping("/api/download")
@RequiredArgsConstructor
public class DownloadFileController {
    
    private final DownloadService downloadService;
    
     /**
      * 下载文件
      * 
      * 下载时触发异步清理：
      * - 使用 Caffeine 缓存标识，避免重复清理
      * - 清理过期目录（日期 < 当前日期 -1 天）
      * 
      * 使用 /** 匹配多段路径（fileId 包含日期/租户/会话/文件名）
      * 
      * @param request HTTP 请求（用于提取 fileId）
      * @return 文件流（Content-Disposition: attachment）
      */
    @GetMapping("/**")
    public ResponseEntity<Resource> downloadFile(HttpServletRequest request) {
        // 1. 从请求 URI 中提取 fileId
        // URI 格式：/api/download/20260831/tenant-1/user-100/report.md
        String requestUri = request.getRequestURI();
        
        // 从 URI 中提取 fileId：移除 /api/download 前缀
        // fileId 格式：20260831/tenant-1/user-100/report.md
        String fileId = extractFileIdFromUri(requestUri, "/api/download");
        
        // 【安全检查】验证 fileId 是否包含路径穿越字符
        if (!validateFileId(fileId)) {
            log.warn("路径穿越攻击检测：fileId={}", fileId);
            return ResponseEntity.badRequest().build();
        }
        
        log.info("下载文件：{}", fileId);
        
        // 2. 异步触发清理过期目录
        // 使用 Caffeine 缓存标识，避免重复清理
        downloadService.cleanupOldDirectoriesAsync();
        
        // 3. 获取文件路径
        Path filePath = downloadService.getFilePath(fileId);
        if (filePath == null) {
            log.warn("文件不存在或已过期：{}", fileId);
            return ResponseEntity.notFound().build();
        }
        
        // 4. 检查文件是否存在
        File file = filePath.toFile();
        if (!file.exists()) {
            log.error("文件不存在：{}", filePath.toAbsolutePath());
            return ResponseEntity.notFound().build();
        }
        
        // 5. 增加下载次数（简化版不实际存储）
        downloadService.incrementDownloadCount(fileId);
        
        // 6. 推断 Content-Type
        String contentType = inferContentType(fileId);
        
        // 7. 提取文件名
        String filename = extractFilename(fileId);
        
        // 8. 返回文件流
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, 
                "attachment; filename=\"" + filename + "\"")
            .body(new FileSystemResource(file));
    }
    
    /**
     * 从 URI 中提取 fileId（防御性方法，优先使用 HandlerMapping 属性）
     * URI 格式：/api/download/{fileId}
     * fileId 格式：dateDir/tenantDir/[sessionDir]/filename
     * 
     * @param requestUri 完整请求 URI
     * @param contextPath 上下文路径（如 /api/download）
     * @return fileId
     */
    private String extractFileIdFromUri(String requestUri, String contextPath) {
        if (requestUri == null || contextPath == null) {
            log.error("URI 或 contextPath 为 null");
            return "";
        }
        
        // 确保 contextPath 以 / 结尾
        String prefix = contextPath.endsWith("/") ? contextPath : contextPath + "/";
        
        if (requestUri.startsWith(prefix)) {
            return requestUri.substring(prefix.length());
        }
        
        // 防御性处理：不应该到这里
        log.warn("请求 URI 格式异常：{}, 期望前缀：{}", requestUri, prefix);
        return requestUri;
    }
    
    /**
     * 【安全检查】验证 fileId 是否合法
     * 防止路径穿越攻击（Path Traversal）
     * 
     * fileId 合法格式：20260831/tenant-1/user-100/report.md
     * fileId 非法格式：../etc/passwd, /etc/passwd, C:\Windows\system32
     * 
     * @param fileId 文件 ID
     * @return true 合法，false 非法
     */
    private boolean validateFileId(String fileId) {
        if (fileId == null || fileId.isEmpty()) {
            log.debug("fileId 为空");
            return false;
        }
        
        // 1. 检查是否包含路径穿越字符 ..
        // 这是最关键的安全检查，阻止跳出 baseDir
        if (fileId.contains("..")) {
            log.debug("fileId 包含路径穿越字符：..");
            return false;
        }
        
        // 2. 检查是否以 / 或 \ 开头（绝对路径）
        // 相对路径可以包含 /，但不能以 / 开头
        if (fileId.startsWith("/") || fileId.startsWith("\\")) {
            log.debug("fileId 以路径分隔符开头（绝对路径）");
            return false;
        }
        
        // 3. 检查是否包含空字符（null byte）攻击
        // 空字符可以截断路径检查
        if (fileId.contains("\u0000")) {
            log.debug("fileId 包含空字符");
            return false;
        }
        
        // 4. 检查是否包含 URL 编码的路径穿越（%2e%2e 等）
        // 注意：Spring 已经解码，但防御性检查
        if (fileId.contains("%2e") || fileId.contains("%2E") || 
            fileId.contains("%2f") || fileId.contains("%2F") ||
            fileId.contains("%5c") || fileId.contains("%5C")) {
            log.debug("fileId 包含 URL 编码字符");
            return false;
        }
        
        // 5. 检查是否包含 Windows 盘符（C:, D: 等）
        // 防止 Windows 绝对路径
        if (fileId.length() >= 2 && fileId.charAt(1) == ':') {
            char driveLetter = Character.toUpperCase(fileId.charAt(0));
            if (driveLetter >= 'A' && driveLetter <= 'Z') {
                log.debug("fileId 包含 Windows 盘符");
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 从文件 ID 中提取文件名
     * 文件 ID 格式：dateDir/tenantDir/[sessionDir]/filename
     */
    private String extractFilename(String fileId) {
        int lastSlashIndex = fileId.lastIndexOf('/');
        if (lastSlashIndex > 0 && lastSlashIndex < fileId.length() - 1) {
            return fileId.substring(lastSlashIndex + 1);
        }
        return fileId;
    }
    
    /**
     * 推断 Content-Type
     */
    private String inferContentType(String fileId) {
        if (fileId.endsWith(".md")) {
            return "text/markdown";
        } else if (fileId.endsWith(".txt")) {
            return "text/plain";
        } else if (fileId.endsWith(".json")) {
            return "application/json";
        } else if (fileId.endsWith(".csv")) {
            return "text/csv";
        } else if (fileId.endsWith(".xml")) {
            return "application/xml";
        } else if (fileId.endsWith(".html") || fileId.endsWith(".htm")) {
            return "text/html";
        } else if (fileId.endsWith(".pdf")) {
            return "application/pdf";
        } else if (fileId.endsWith(".doc")) {
            return "application/msword";
        } else if (fileId.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        } else if (fileId.endsWith(".xls")) {
            return "application/vnd.ms-excel";
        } else if (fileId.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        } else {
            return "application/octet-stream";
        }
    }
}
