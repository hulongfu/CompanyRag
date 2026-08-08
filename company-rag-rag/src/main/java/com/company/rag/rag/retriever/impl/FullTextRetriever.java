package com.company.rag.rag.retriever.impl;

import com.company.rag.rag.model.RagResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全文检索器（PostgreSQL tsvector）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FullTextRetriever {
    
    private final JdbcTemplate jdbcTemplate;
    
    /**
     * 全文检索
     * @param query 查询文本
     * @param topK 返回数量
     * @return 检索结果
     */
    public List<RagResult.ChunkResult> retrieve(String query, int topK) {
        String tsQuery = buildTsQuery(query);
        
        String sql = """
            SELECT id, content, metadata,
                   ts_rank(content_tsv, to_tsquery('pg_catalog.simple', ?)) AS score
            FROM vector_store
            WHERE content_tsv @@ to_tsquery('pg_catalog.simple', ?)
            ORDER BY score DESC
            LIMIT ?
        """;
        
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tsQuery, tsQuery, topK);
        return convertToChunkResults(rows);
    }
    
    /**
     * 构建 PostgreSQL tsquery
     * 将用户查询转换为 tsquery 语法（& 连接，前缀匹配）
     * 
     * 注意：pg_catalog.simple 分词器对中文支持有限，这里采用简化策略：
     * - 英文单词：使用前缀匹配（word:*）
     * - 中文字符：直接使用原词（依赖 PostgreSQL 中文分词扩展或精确匹配）
     * - 多个词之间用 & 连接
     */
    private String buildTsQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "";
        }
        
        // 提取所有连续的字母数字序列作为英文词
        List<String> terms = new ArrayList<>();
        
        // 使用正则提取英文单词和数字
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[a-zA-Z0-9]+");
        java.util.regex.Matcher matcher = pattern.matcher(query);
        
        int lastEnd = 0;
        while (matcher.find()) {
            // 添加英文词（前缀匹配）
            terms.add(matcher.group() + ":*");
            
            // 检查英文词之间的中文部分
            if (matcher.start() > lastEnd) {
                String chinesePart = query.substring(lastEnd, matcher.start()).trim();
                if (!chinesePart.isEmpty()) {
                    // 中文部分直接添加（精确匹配）
                    terms.add(escapeTsQuery(chinesePart));
                }
            }
            lastEnd = matcher.end();
        }
        
        // 添加剩余的中文部分
        if (lastEnd < query.length()) {
            String chinesePart = query.substring(lastEnd).trim();
            if (!chinesePart.isEmpty()) {
                terms.add(escapeTsQuery(chinesePart));
            }
        }
        
        // 如果没有提取到任何词，使用整个查询（精确匹配）
        if (terms.isEmpty()) {
            terms.add(escapeTsQuery(query.trim()));
        }
        
        return String.join(" & ", terms);
    }
    
    /**
     * 转义 tsquery 特殊字符
     * PostgreSQL tsquery 特殊字符：& | ! ( ) : * ' " \
     * 
     * 注意：如果输入包含空格，会分割成多个词并用 & 连接
     */
    private String escapeTsQuery(String term) {
        // 按空格分割成多个词（支持短语查询）
        String[] words = term.trim().split("\\s+");
        List<String> escapedWords = new ArrayList<>();
        
        for (String word : words) {
            if (!word.isEmpty()) {
                // 移除可能导致语法错误的特殊字符
                String escaped = word.replaceAll("[&|!()::*'\"\\\\]", " ").trim();
                if (!escaped.isEmpty()) {
                    escapedWords.add(escaped);
                }
            }
        }
        
        // 用 & 连接所有词
        return String.join(" & ", escapedWords);
    }
    
    private List<RagResult.ChunkResult> convertToChunkResults(List<Map<String, Object>> rows) {
        List<RagResult.ChunkResult> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            RagResult.ChunkResult cr = new RagResult.ChunkResult();
            cr.setChunkId(row.get("id") != null ? row.get("id").toString() : "");
            cr.setContent((String) row.get("content"));
            cr.setKeywordScore(((Number) row.get("score")).doubleValue());
            
            // 解析 metadata JSON：PostgreSQL 返回的是 PGobject，需要转换为 String
            Object metadataObj = row.get("metadata");
            String metadata;
            if (metadataObj instanceof PGobject) {
                try {
                    metadata = ((PGobject) metadataObj).getValue();
                } catch (Exception e) {
                    log.warn("解析 metadata 失败：{}", e.getMessage());
                    metadata = "{}";
                }
            } else {
                metadata = metadataObj != null ? metadataObj.toString() : "{}";
            }
            // TODO: 解析 metadata 提取 documentName, chunkIndex 等
            cr.setDocumentName("未知");
            
            results.add(cr);
        }
        return results;
    }
}
