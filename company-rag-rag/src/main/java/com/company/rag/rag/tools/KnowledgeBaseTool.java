package com.company.rag.rag.tools;

import com.company.rag.common.tool.ToolCallRecorder;
import com.company.rag.rag.model.KnowledgeBaseResult;
import com.company.rag.rag.model.RagQuery;
import com.company.rag.rag.model.RagResult;
import com.company.rag.rag.service.RagSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MCP 工具：企业知识库智能问答（RAG）
 * 将自然语言问题转换为 RAG 检索，返回带引用来源的答案
 */
@Slf4j
@Component
public class KnowledgeBaseTool {
    
    private final RagSearchService ragSearchService;
    private final ToolCallRecorder recorder;
    
    public KnowledgeBaseTool(RagSearchService ragSearchService,
                            ToolCallRecorder recorder) {
        this.ragSearchService = ragSearchService;
        this.recorder = recorder;
    }
    
    @Tool(
        name = "searchKnowledgeBase",
        description = """
            在企业知识库文档中检索信息，包括 Markdown（.md）、PDF、Word（.docx）、TXT 文件。
            
            适用场景：
            - 查询 README、设计文档、使用手册、FAQ、流程规范、项目说明
            - 例如："怎么申请测试环境？"、"公司请假流程是什么？"、"项目架构是怎样的？"
            
            不适用场景（请调用其他工具）：
            - 代码检索 -> 使用 code_search
            - 数据库查询 -> 使用 database_query
            - API 文档 -> 使用 api_doc
            """
    )
    public KnowledgeBaseResult searchKnowledgeBase(
            @ToolParam(description = "用户自然语言问题，例如：怎么申请测试环境？") String question,
            @ToolParam(description = "返回文档片段数量上限，默认 5", required = false) Integer topK) {
        
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("question", question);
        if (topK != null) {
            args.put("topK", topK);
        }
        recorder.recordStart("searchKnowledgeBase", args);
        
        try {
            // 参数校验
            if (question == null || question.trim().isEmpty()) {
                recorder.recordEnd("searchKnowledgeBase", "failed");
                return KnowledgeBaseResult.failed("问题不能为空");
            }
            
            // 调用 RAG 引擎（混合检索 + Rerank）
            int effectiveTopK = (topK == null || topK <= 0) ? 5 : topK;
            RagQuery query = new RagQuery();
            query.setQuery(question);
            query.setTopK(effectiveTopK);
            RagResult result = ragSearchService.search(query);
            
            // 转换为 KnowledgeBaseResult
            KnowledgeBaseResult response = convertToKnowledgeBaseResult(result);
            
            if (response.isSuccess()) {
                recorder.recordEnd("searchKnowledgeBase", "success");
            } else {
                recorder.recordEnd("searchKnowledgeBase", "failed");
            }
            
            return response;
            
        } catch (Exception e) {
            log.error("知识库工具调用失败：question={}, err={}", question, e.getMessage());
            recorder.recordEnd("searchKnowledgeBase", "failed");
            return KnowledgeBaseResult.failed("工具调用失败：" + e.getMessage());
        }
    }
    
    /**
     * 将 RagResult 转换为 KnowledgeBaseResult
     */
    private KnowledgeBaseResult convertToKnowledgeBaseResult(RagResult ragResult) {
        if (ragResult == null || ragResult.getChunks() == null || ragResult.getChunks().isEmpty()) {
            return KnowledgeBaseResult.failed("未找到相关信息");
        }
        
        // 提取引用来源
        List<KnowledgeBaseResult.Citation> citations = ragResult.getChunks().stream()
                .map(chunk -> new KnowledgeBaseResult.Citation(
                        chunk.getDocumentName(),  // 文件名
                        chunk.getContent().length() > 200 
                            ? chunk.getContent().substring(0, 200) + "..." 
                            : chunk.getContent(),  // 内容预览
                        chunk.getFinalScore(),  // 相似度分数
                        chunk.getChunkIndex()  // chunk 索引
                ))
                .collect(Collectors.toList());
        
        // 使用 LLM 生成的答案（如果 RAG 引擎已调用 LLM）
        // 或者返回检索到的文档片段
        String answer = ragResult.getAnswer() != null 
                ? ragResult.getAnswer() 
                : buildAnswerFromChunks(ragResult.getChunks());
        
        return KnowledgeBaseResult.ok(answer, citations);
    }
    
    /**
     * 从文档片段构建答案（当 RAG 引擎未调用 LLM 时）
     */
    private String buildAnswerFromChunks(List<RagResult.ChunkResult> chunks) {
        StringBuilder sb = new StringBuilder("找到以下相关文档片段：\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            RagResult.ChunkResult chunk = chunks.get(i);
            sb.append(String.format("[%d] 来源：%s\n%s\n\n", 
                    i + 1, 
                    chunk.getDocumentName(), 
                    chunk.getContent()));
        }
        return sb.toString();
    }
}
