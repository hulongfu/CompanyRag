package com.company.rag.agent.tool;

import com.company.rag.agent.service.DownloadRecord;
import com.company.rag.agent.service.DownloadService;
import com.company.rag.tenant.context.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 下载工具
 * 
 * 提供文件下载能力，将内容写入文件并生成下载链接
 * 适用于：
 * - 导出 API 文档
 * - 导出检索结果
 * - 导出分析报告
 * - 导出代码文件
 * 
 * 实现 AgentTool 接口以被 AgentToolRegistry 自动注册
 */
@Slf4j
@Component
public class DownloadTool implements AgentTool {
    
    @Autowired
    private DownloadService downloadService;
    
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    @Override
    public String getName() {
        return "download_file";
    }
    
    @Override
    public String getDescription() {
        return "将内容写入文件并生成下载链接，用户可点击下载获取文件。适用于导出 API 文档、检索结果、分析报告、代码文件等场景。";
    }
    
    @Override
    public Map<String, Object> getParameterSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "content", Map.of(
                        "type", "string",
                        "description", "文件内容"
                ),
                "filename", Map.of(
                        "type", "string",
                        "description", "文件名，如 report.md、data.csv、code.py"
                ),
                "contentType", Map.of(
                        "type", "string",
                        "description", "文件类型（可选），如 text/markdown、text/plain、application/json"
                )
        ));
        schema.put("required", new String[]{"content", "filename"});
        return schema;
    }
    
    /**
     * 下载文件工具方法
     * 
     * @param params 参数 Map
     * @return 下载信息（包含下载链接）
     */
    @Override
    public String execute(Map<String, Object> params) {
        if (params == null) {
            return "❌ 文件生成失败：参数不能为空";
        }
        
        try {
            String content = (String) params.get("content");
            String filename = (String) params.get("filename");
            String contentType = (String) params.get("contentType");
            
            if (content == null || content.isEmpty()) {
                return "❌ 文件生成失败：内容不能为空";
            }
            
            if (filename == null || filename.isEmpty()) {
                return "❌ 文件生成失败：文件名不能为空";
            }
            
            // 1. 获取当前租户 ID
            Long tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                log.warn("未找到租户上下文，使用默认租户 ID=1");
                tenantId = 1L;
            }
            
            log.info("创建下载文件：tenantId={}, filename={}, contentType={}, contentLength={}",
                tenantId, filename, contentType, content.length());
            
            // 2. 调用 Service 生成文件
            DownloadRecord record = downloadService.createDownloadFile(
                tenantId,
                content,
                filename,
                contentType
            );
            
            // 3. 返回下载信息（自然语言格式，方便 Agent 理解）
            return String.format("""
                ✅ 文件已生成成功！
                
                **文件信息**：
                - 文件名：%s
                - 大小：%d 字节（%.2f KB）
                - 类型：%s
                - 创建时间：%s
                - 过期时间：%s
                
                **下载链接**：
                %s
                
                用户可点击下载链接获取文件。文件将在 %s 小时后自动删除。
                """,
                record.getFilename(),
                record.getSize(),
                record.getSize() / 1024.0,
                record.getContentType(),
                record.getCreatedAt().format(FORMATTER),
                record.getExpiresAt().format(FORMATTER),
                record.getDownloadUrl(),
                record.getExpiresAt().getHour() - record.getCreatedAt().getHour()
            );
            
        } catch (IllegalArgumentException e) {
            log.error("参数错误：{}", e.getMessage());
            return "❌ 文件生成失败：" + e.getMessage();
        } catch (Exception e) {
            log.error("文件生成异常", e);
            return "❌ 文件生成失败：" + e.getMessage();
        }
    }
}
