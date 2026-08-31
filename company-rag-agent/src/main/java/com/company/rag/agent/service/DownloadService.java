package com.company.rag.agent.service;

import com.company.rag.agent.config.DownloadConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 下载服务
 * 
 * 提供文件下载管理能力：
 * - 创建下载文件
 * - 获取下载记录
 * - 删除下载文件
 * - 清理过期文件
 */
@Slf4j
@Service
public class DownloadService {
    
    @Autowired
    private DownloadConfig downloadConfig;
    
    /**
     * 下载记录存储（内存缓存）
     * Key: fileId, Value: DownloadRecord
     */
    private final Map<String, DownloadRecord> downloadRecords = new ConcurrentHashMap<>();
    
    /**
     * 文件写入锁（避免并发冲突）
     * Key: fileId, Value: Lock
     */
    private final Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();
    
    /**
     * 初始化：创建临时目录
     */
    @PostConstruct
    public void init() {
        try {
            Path tempDir = Paths.get(downloadConfig.getTempDir());
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
                log.info("创建下载临时目录：{}", tempDir.toAbsolutePath());
            }
            log.info("下载服务初始化完成，临时目录：{}, 保留时间：{}小时", 
                downloadConfig.getTempDir(), downloadConfig.getRetentionHours());
        } catch (IOException e) {
            log.error("创建下载临时目录失败：{}", downloadConfig.getTempDir(), e);
            throw new RuntimeException("下载服务初始化失败", e);
        }
    }
    
    /**
     * 创建下载文件
     * 
     * @param tenantId 租户 ID
     * @param content 文件内容
     * @param filename 文件名
     * @param contentType MIME 类型（可选）
     * @return 下载记录
     */
    public DownloadRecord createDownloadFile(
        Long tenantId,
        String content,
        String filename,
        String contentType
    ) {
        // 1. 验证文件名安全性
        validateFilename(filename);
        
        // 2. 验证文件大小
        long contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > downloadConfig.getMaxFileSize()) {
            throw new IllegalArgumentException(
                String.format("文件大小超过限制（%d MB）", downloadConfig.getMaxFileSize() / 1024 / 1024));
        }
        
        // 3. 生成文件 ID 和路径
        String fileId = UUID.randomUUID().toString().replace("-", "");
        String safeFilename = sanitizeFilename(filename);
        String filePath = buildFilePath(tenantId, fileId, safeFilename);
        
        // 4. 自动推断 Content-Type（如果未指定）
        if (contentType == null || contentType.isEmpty()) {
            contentType = inferContentType(filename);
        }
        
        // 5. 计算过期时间
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(downloadConfig.getRetentionHours());
        
        // 6. 创建下载记录
        DownloadRecord record = DownloadRecord.builder()
            .fileId(fileId)
            .tenantId(tenantId)
            .filename(safeFilename)
            .filePath(filePath)
            .contentType(contentType)
            .size(contentBytes)
            .expiresAt(expiresAt)
            .createdAt(LocalDateTime.now())
            .downloadedCount(0)
            .build();
        
        // 7. 获取文件锁
        ReentrantLock lock = fileLocks.computeIfAbsent(fileId, k -> new ReentrantLock());
        lock.lock();
        
        try {
            // 8. 写入文件
            Path path = Paths.get(filePath);
            Path parentDir = path.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
            log.debug("文件写入成功：{}, 大小：{} bytes", filePath, contentBytes);
            
            // 9. 存储下载记录
            downloadRecords.put(fileId, record);
            
        } catch (IOException e) {
            log.error("写入文件失败：{}", filePath, e);
            throw new RuntimeException("写入文件失败", e);
        } finally {
            lock.unlock();
            // 清理锁（避免内存泄漏）
            fileLocks.remove(fileId);
        }
        
        return record;
    }
    
    /**
     * 获取下载记录
     * 
     * @param fileId 文件 ID
     * @return 下载记录（不存在返回 null）
     */
    public DownloadRecord getDownloadFile(String fileId) {
        DownloadRecord record = downloadRecords.get(fileId);
        
        // 检查是否过期
        if (record != null && LocalDateTime.now().isAfter(record.getExpiresAt())) {
            log.warn("下载文件已过期：{}, 过期时间：{}", fileId, record.getExpiresAt());
            deleteDownloadFile(fileId);
            return null;
        }
        
        return record;
    }
    
    /**
     * 删除下载文件（物理删除 + 记录删除）
     * 
     * @param fileId 文件 ID
     */
    public void deleteDownloadFile(String fileId) {
        DownloadRecord record = downloadRecords.remove(fileId);
        if (record != null) {
            try {
                Path path = Paths.get(record.getFilePath());
                if (Files.exists(path)) {
                    Files.delete(path);
                    log.debug("删除文件：{}", record.getFilePath());
                }
            } catch (IOException e) {
                log.error("删除文件失败：{}", record.getFilePath(), e);
            }
        }
    }
    
    /**
     * 增加下载次数
     * 
     * @param fileId 文件 ID
     */
    public void incrementDownloadCount(String fileId) {
        DownloadRecord record = downloadRecords.get(fileId);
        if (record != null) {
            record.setDownloadedCount(record.getDownloadedCount() + 1);
        }
    }
    
    /**
     * 清理过期文件（定时任务调用）
     * 
     * @return 清理的文件数量
     */
    public int cleanupExpiredFiles() {
        LocalDateTime now = LocalDateTime.now();
        int cleaned = 0;
        
        for (Map.Entry<String, DownloadRecord> entry : downloadRecords.entrySet()) {
            String fileId = entry.getKey();
            DownloadRecord record = entry.getValue();
            
            if (now.isAfter(record.getExpiresAt())) {
                deleteDownloadFile(fileId);
                cleaned++;
                log.debug("清理过期文件：{}, 过期时间：{}", fileId, record.getExpiresAt());
            }
        }
        
        if (cleaned > 0) {
            log.info("清理过期下载文件：{} 个", cleaned);
        }
        
        return cleaned;
    }
    
    /**
     * 验证文件名安全性
     */
    private void validateFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        
        // 检查是否包含路径遍历
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw new IllegalArgumentException("文件名包含非法字符");
        }
        
        // 检查是否包含危险字符
        if (!filename.matches("^[a-zA-Z0-9._\\-\\u4e00-\\u9fa5]+$")) {
            throw new IllegalArgumentException("文件名包含不支持的字符");
        }
    }
    
    /**
     * 清理文件名（保留安全字符）
     */
    private String sanitizeFilename(String filename) {
        // 移除路径分隔符
        filename = filename.replaceAll("[/\\\\]", "_");
        // 移除连续的点
        filename = filename.replaceAll("\\.{2,}", "_");
        // 移除开头的点
        filename = filename.replaceAll("^\\.", "_");
        return filename;
    }
    
    /**
     * 构建文件存储路径
     */
    private String buildFilePath(Long tenantId, String fileId, String filename) {
        return String.format("%s/%d/%s/%s",
            downloadConfig.getTempDir(),
            tenantId,
            fileId.substring(0, 2), // 按文件 ID 前 2 位分目录
            filename
        );
    }
    
    /**
     * 推断 Content-Type
     */
    private String inferContentType(String filename) {
        if (filename.endsWith(".md")) {
            return "text/markdown";
        } else if (filename.endsWith(".txt")) {
            return "text/plain";
        } else if (filename.endsWith(".json")) {
            return "application/json";
        } else if (filename.endsWith(".csv")) {
            return "text/csv";
        } else if (filename.endsWith(".xml")) {
            return "application/xml";
        } else if (filename.endsWith(".html") || filename.endsWith(".htm")) {
            return "text/html";
        } else if (filename.endsWith(".pdf")) {
            return "application/pdf";
        } else if (filename.endsWith(".doc") || filename.endsWith(".docx")) {
            return "application/msword";
        } else if (filename.endsWith(".xls") || filename.endsWith(".xlsx")) {
            return "application/vnd.ms-excel";
        } else {
            return "application/octet-stream"; // 默认二进制流
        }
    }
}
