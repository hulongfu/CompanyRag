package com.company.rag.agent.tool;

import com.company.rag.agent.service.DownloadService;
import com.company.rag.tenant.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 下载工具（简化版）
 * 
 * 提供文件下载能力，将内容写入文件并生成下载链接
 * 适用于：
 * - 导出 API 文档
 * - 导出检索结果
 * - 导出分析报告
 * - 导出代码文件
 * 
 * 实现 AgentTool 接口以被 AgentToolRegistry 自动注册
 * 
 * 参数说明：
 * - content: 文件内容（必填）
 * - filename: 文件名（可选，未指定则自动生成）
 * - contentType: 文件类型（可选，未指定则根据扩展名推断）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadTool implements AgentTool {
    
    private final DownloadService downloadService;
    
    @Override
    public String getName() {
        return "download_file";
    }
    
    @Override
    public String getDescription() {
        return "将内容写入文件并生成下载链接，用户可点击下载获取文件。适用于导出 API 文档、检索结果、分析报告、代码文件等场景。" +
               "filename 参数可选，未指定则自动生成文件名（时间戳 + 随机数）。" +
               "sessionId 由系统自动从当前会话上下文获取，无需手动传递。";
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
                        "description", "文件名（可选），如 report.md、data.csv、code.py。未指定则自动生成"
                ),
                "contentType", Map.of(
                        "type", "string",
                        "description", "文件类型（可选），如 text/markdown、text/plain、application/json"
                )
        ));
        schema.put("required", new String[]{"content"});
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
            // 从上下文获取 sessionId（不是从 LLM 参数获取，因为那是不可信输入）
            String sessionId = TenantContext.getSessionId();
            
            if (content == null || content.isEmpty()) {
                return "❌ 文件生成失败：内容不能为空";
            }
            
            // 1. 获取当前租户 ID
            Long tenantId = TenantContext.getTenantId();
            if (tenantId == null) {
                log.error("租户上下文缺失，无法创建下载文件");
                throw new IllegalStateException("租户上下文缺失，无法创建下载文件");
            }
            
            log.info("创建下载文件：tenantId={}, sessionId={}, filename={}, contentType={}, contentLength={}",
                tenantId, sessionId, filename, contentType, content.length());
            
            // 2. 调用 Service 生成文件（传递 sessionId 用于隔离不同会话的文件）
            String fileId = downloadService.createDownloadFile(
                tenantId,
                sessionId,
                content,
                filename,
                contentType
            );
            
            // 3. 构建下载链接 (使用 Markdown 链接格式，前端可渲染为可点击链接)
            String downloadUrl = "/api/download/" + fileId;
            String markdownLink = String.format("[📥 点击下载 `%s`](%s)", 
                filename != null ? filename : "下载文件", downloadUrl);
            
            // 4. 返回下载信息 (自然语言格式，方便 Agent 理解)
            return String.format("""
                ✅ 文件已生成成功！
                
                **文件信息**:
                - 文件名：%s
                - 类型：%s
                
                **下载链接**:
                %s
                
                > ⏰ 提示：文件将在明天自动清理 (过期目录机制),请及时下载保存。
                """,
                filename != null ? filename : "自动生成",
                contentType != null ? contentType : "自动推断",
                markdownLink
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
