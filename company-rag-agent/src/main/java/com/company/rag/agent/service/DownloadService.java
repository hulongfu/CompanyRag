package com.company.rag.agent.service;

import com.company.rag.agent.config.DownloadConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 下载服务（简化版 - 基于文件系统）
 * 
 * 设计哲学：
 * 1. 文件存储：按日期分目录（yyyyMMdd），重启不丢失
 * 2. 清理策略：异步清理过期目录（> 昨天的目录）
 * 3. 无内存依赖：不存储 DownloadRecord，基于文件系统
 * 4. 智能清理：使用 Caffeine 缓存清理标识，避免重复清理
 * 
 * 目录结构：
 * ${agent.download.base-dir}/
 * ├── 20260831/                # 日期目录
 * │   ├── tenant-1/            # 租户目录
 * │   │   ├── user-100/        # 用户目录
 * │   │   │   └── api-doc.md   # 文件
 * │   │   └── user-101/
 * │   └── tenant-2/
 * └── 20260830/                # 昨天的目录（明天清理）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {
    
    private final DownloadConfig downloadConfig;
    
    /**
     * 下载清理标识缓存管理器
     * 使用 @Qualifier 指定注入 downloadCleanupCacheManager
     */
    @Autowired
    @Qualifier("downloadCleanupCacheManager")
    private CacheManager cleanupCacheManager;
    
    /**
     * 日期格式化：yyyyMMdd
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    
    /**
     * 初始化：创建根目录
     */
    @PostConstruct
    public void init() {
        // 创建根目录
        try {
            Path baseDir = Paths.get(downloadConfig.getBaseDir());
            if (!Files.exists(baseDir)) {
                Files.createDirectories(baseDir);
                log.info("创建下载文件根目录：{}", baseDir.toAbsolutePath());
            }
            log.info("下载服务初始化完成，根目录：{}, 清理标识过期时间：{}小时", 
                downloadConfig.getBaseDir(), downloadConfig.getCleanupExpireHours());
        } catch (IOException e) {
            log.error("创建下载文件根目录失败：{}", downloadConfig.getBaseDir(), e);
            throw new RuntimeException("下载服务初始化失败", e);
        }
    }
    
    /**
     * 创建下载文件
     * 
     * @param tenantId 租户 ID
     * @param userId 用户 ID（可选，null 则不使用用户目录）
     * @param content 文件内容
     * @param filename 文件名（用户指定或 null 使用自动生成）
     * @param contentType MIME 类型（可选，null 则自动推断）
     * @return 文件 ID（用于下载）
     */
    public String createDownloadFile(
        Long tenantId,
        Long userId,
        String content,
        String filename,
        String contentType
    ) {
        // 1. 验证并清理文件名
        validateFilename(filename);
        String safeFilename = sanitizeFilename(filename);
        
        // 2. 如果用户未指定文件名，自动生成
        if (safeFilename == null || safeFilename.isEmpty()) {
            safeFilename = generateFilename(contentType);
        }
        
        // 3. 验证文件大小
        long contentBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > downloadConfig.getMaxFileSize()) {
            throw new IllegalArgumentException(
                String.format("文件大小超过限制（%d MB）", downloadConfig.getMaxFileSize() / 1024 / 1024));
        }
        
        // 4. 自动推断 Content-Type（如果未指定）
        if (contentType == null || contentType.isEmpty()) {
            contentType = inferContentType(safeFilename);
        }
        
        // 5. 构建文件路径（按日期 + 租户 + 用户分目录）
        String dateDir = LocalDate.now().format(DATE_FORMATTER);
        String tenantDir = "tenant-" + tenantId;
        String userDir = (userId != null) ? "user-" + userId : null;
        
        Path filePath = buildFilePath(dateDir, tenantDir, userDir, safeFilename);
        
        // 6. 写入文件
        try {
            Path parentDir = filePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }
            
            Files.write(filePath, content.getBytes(StandardCharsets.UTF_8));
            log.info("文件写入成功：{}, 大小：{} bytes, 类型：{}", 
                filePath.toAbsolutePath(), contentBytes, contentType);
            
        } catch (IOException e) {
            log.error("写入文件失败：{}", filePath.toAbsolutePath(), e);
            throw new RuntimeException("写入文件失败", e);
        }
        
        // 7. 返回文件 ID（文件路径相对于 baseDir 的相对路径作为 ID）
        // 格式：dateDir/tenantDir/[userDir]/filename
        String fileId = buildFileId(dateDir, tenantDir, userDir, safeFilename);
        log.debug("生成文件 ID: {}", fileId);
        
        return fileId;
    }
    
    /**
     * 获取文件路径（用于下载）
     * 
     * @param fileId 文件 ID
     * @return 文件路径，不存在返回 null
     */
    public Path getFilePath(String fileId) {
        // 【安全检查】验证 fileId 是否为空
        if (fileId == null || fileId.isEmpty()) {
            log.warn("fileId 为空");
            return null;
        }
        
        // 【安全检查】验证 fileId 是否包含路径穿越字符（防御性检查，Controller 层应该已检查）
        if (fileId.contains("..")) {
            log.warn("fileId 包含路径穿越字符：{}", fileId);
            return null;
        }
        
        // 1. 构建完整路径
        Path baseDirPath = Paths.get(downloadConfig.getBaseDir());
        Path fullPath = baseDirPath.resolve(fileId).normalize();
        
        // 【安全检查】验证规范化后的路径是否在 baseDir 内
        // 防止通过符号链接或其他方式跳出 baseDir
        if (!fullPath.startsWith(baseDirPath.normalize())) {
            log.error("路径穿越攻击检测：fileId={}, fullPath={}", fileId, fullPath.toAbsolutePath());
            return null;
        }
        
        // 2. 检查文件是否存在
        if (!Files.exists(fullPath)) {
            log.warn("文件不存在：{}", fullPath.toAbsolutePath());
            return null;
        }
        
        return fullPath;
    }
    
    /**
     * 增加下载次数（记录到 Caffeine 缓存）
     * 注意：简化版本不持久化下载次数，仅用于演示
     * 
     * @param fileId 文件 ID
     */
    public void incrementDownloadCount(String fileId) {
        // 简化版本：不实际存储下载次数
        // 如需持久化，可使用 Redis 或数据库
        log.debug("下载次数 +1: {}", fileId);
    }
    
    /**
     * 异步清理过期目录
     * 使用 Spring CacheManager 管理清理标识，避免重复清理
     */
    @Async
    public void cleanupOldDirectoriesAsync() {
        String todayKey = LocalDate.now().format(DATE_FORMATTER);
        
        // 从指定的 CacheManager 获取缓存
        org.springframework.cache.Cache cleanupCache = cleanupCacheManager.getCache("downloadCleanup");
        if (cleanupCache == null) {
            log.warn("未找到 downloadCleanup 缓存，使用默认 Caffeine 缓存");
            // 降级处理：直接清理，不检查缓存
            cleanupOldDirectories();
            return;
        }
        
        // 检查今天是否已清理
        if (cleanupCache.get(todayKey) != null) {
            log.debug("今天已执行过清理，跳过：{}", todayKey);
            return;
        }
        
        // 执行清理
        cleanupOldDirectories();
        
        // 标记已清理
        cleanupCache.put(todayKey, Boolean.TRUE);
        log.info("标记清理完成：{}", todayKey);
    }
    
    /**
     * 清理过期目录（同步执行）
     * 删除日期 < 当前日期 -1 天的目录
     */
    private void cleanupOldDirectories() {
        File baseDir = new File(downloadConfig.getBaseDir());
        if (!baseDir.exists() || !baseDir.isDirectory()) {
            log.warn("下载根目录不存在或不是目录：{}", downloadConfig.getBaseDir());
            return;
        }
        
        File[] dateDirs = baseDir.listFiles(File::isDirectory);
        if (dateDirs == null || dateDirs.length == 0) {
            return;
        }
        
        LocalDate yesterday = LocalDate.now().minusDays(1);
        int cleanedCount = 0;
        
        for (File dateDir : dateDirs) {
            String dirName = dateDir.getName();
            
            try {
                // 解析目录名（yyyyMMdd）
                LocalDate dirDate = LocalDate.parse(dirName, DATE_FORMATTER);
                
                // 如果早于昨天，删除整个目录
                if (dirDate.isBefore(yesterday)) {
                    deleteDirectory(dateDir);
                    cleanedCount++;
                    log.info("清理过期目录：{} ({})", dateDir.getAbsolutePath(), dirName);
                }
                
            } catch (Exception e) {
                log.error("解析目录名失败，跳过：{}", dirName, e);
            }
        }
        
        if (cleanedCount > 0) {
            log.info("清理过期目录完成，删除 {} 个目录", cleanedCount);
        } else {
            log.debug("无需清理过期目录");
        }
    }
    
    /**
     * 删除目录（递归删除）
     */
    private void deleteDirectory(File directory) throws IOException {
        if (!directory.exists()) {
            return;
        }
        
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    Files.delete(file.toPath());
                }
            }
        }
        
        Files.delete(directory.toPath());
    }
    
    /**
     * 验证文件名安全性
     */
    private void validateFilename(String filename) {
        if (filename != null && !filename.trim().isEmpty()) {
            // 检查是否包含路径遍历
            if (filename.contains("..")) {
                throw new IllegalArgumentException("文件名包含非法字符 (..)");
            }
            
            // 检查是否以 / 或 \ 开头
            if (filename.startsWith("/") || filename.startsWith("\\")) {
                throw new IllegalArgumentException("文件名不能以路径分隔符开头");
            }
        }
    }
    
    /**
     * 清理文件名（保留安全字符）
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return null;
        }
        
        // 移除路径分隔符
        filename = filename.replaceAll("[/\\\\]", "_");
        // 移除连续的点
        filename = filename.replaceAll("\\.{2,}", "_");
        // 移除开头的点
        filename = filename.replaceAll("^\\.", "_");
        // 移除首尾空格
        filename = filename.trim();
        
        return filename.isEmpty() ? null : filename;
    }
    
    /**
     * 生成文件名（时间戳 + 随机数）
     */
    private String generateFilename(String contentType) {
        String timestamp = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        String random = UUID.randomUUID().toString().substring(0, 8);
        
        // 根据 Content-Type 推断扩展名
        String extension = inferExtension(contentType);
        
        return timestamp + "_" + random + extension;
    }
    
    /**
     * 根据 Content-Type 推断扩展名
     */
    private String inferExtension(String contentType) {
        if (contentType == null) {
            return ".txt";
        }
        
        switch (contentType) {
            case "text/markdown":
                return ".md";
            case "text/plain":
                return ".txt";
            case "application/json":
                return ".json";
            case "text/csv":
                return ".csv";
            case "application/xml":
            case "text/xml":
                return ".xml";
            case "text/html":
                return ".html";
            case "application/pdf":
                return ".pdf";
            case "application/msword":
                return ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
                return ".docx";
            case "application/vnd.ms-excel":
                return ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
                return ".xlsx";
            default:
                return ".txt";
        }
    }
    
    /**
     * 推断 Content-Type
     */
    private String inferContentType(String filename) {
        if (filename == null) {
            return "text/plain";
        }
        
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
        } else if (filename.endsWith(".doc")) {
            return "application/msword";
        } else if (filename.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        } else if (filename.endsWith(".xls")) {
            return "application/vnd.ms-excel";
        } else if (filename.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        } else {
            return "text/plain";
        }
    }
    
    /**
     * 构建文件路径
     */
    private Path buildFilePath(String dateDir, String tenantDir, String userDir, String filename) {
        Path path = Paths.get(downloadConfig.getBaseDir(), dateDir, tenantDir);
        
        if (userDir != null) {
            path = path.resolve(userDir);
        }
        
        return path.resolve(filename);
    }
    
    /**
     * 构建文件 ID（相对路径）
     */
    private String buildFileId(String dateDir, String tenantDir, String userDir, String filename) {
        if (userDir != null) {
            return String.format("%s/%s/%s/%s", dateDir, tenantDir, userDir, filename);
        } else {
            return String.format("%s/%s/%s", dateDir, tenantDir, filename);
        }
    }
}
