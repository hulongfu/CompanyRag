package com.company.rag.rag.prompt;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Prompt模板管理
 * 支持可观测：记录每次使用的Prompt内容与Token消耗
 */
@Component
public class PromptTemplate {

    /**
     * 构建RAG问答Prompt
     */
    public String buildChatPrompt(String query, String context) {
        return String.format("""
                你是一个专业的企业知识库助手。请基于以下提供的参考资料，准确、简洁地回答用户的问题。
                
                ## 注意事项
                1. 如果参考资料不足以回答问题，请明确说明"根据现有资料无法回答"
                2. 引用资料时，标注来源文档名称
                3. 用中文回答
                4. 回答应结构化，使用编号或要点
                
                ## 参考资料
                %s
                
                ## 当前日期
                %s
                
                ## 用户问题
                %s
                
                ## 回答
                """, context, LocalDate.now(), query);
    }

    /**
     * 构建带对话历史的Prompt
     */
    public String buildChatWithHistoryPrompt(String query, String context, List<String> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个专业的企业知识库助手。\n\n");
        sb.append("## 对话历史\n");
        for (int i = 0; i < history.size(); i++) {
            sb.append(history.get(i)).append("\n");
        }
        sb.append("\n## 参考资料\n").append(context);
        sb.append("\n\n## 用户问题\n").append(query);
        sb.append("\n\n## 回答\n");
        return sb.toString();
    }

    /**
     * 构建文档摘要Prompt
     */
    public String buildSummaryPrompt(String documentContent) {
        return String.format("""
                请对以下文档内容进行摘要，要求：
                1. 提炼核心要点，不超过200字
                2. 保持关键数据和技术细节
                3. 使用要点形式输出
                
                ## 文档内容
                %s
                
                ## 摘要
                """, documentContent);
    }
}
