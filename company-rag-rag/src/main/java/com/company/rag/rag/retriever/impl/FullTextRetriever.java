package com.company.rag.rag.retriever.impl;

import com.company.rag.rag.model.RagResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
     */
    private String buildTsQuery(String query) {
        String[] terms = query.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.length; i++) {
            if (i > 0) sb.append(" & ");
            sb.append(terms[i].replaceAll("[^a-zA-Z0-9]", ""));
            sb.append(":*");  // 前缀匹配
        }
        return sb.toString();
    }
    
    private List<RagResult.ChunkResult> convertToChunkResults(List<Map<String, Object>> rows) {
        List<RagResult.ChunkResult> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            RagResult.ChunkResult cr = new RagResult.ChunkResult();
            cr.setChunkId(row.get("id") != null ? row.get("id").toString() : "");
            cr.setContent((String) row.get("content"));
            cr.setKeywordScore(((Number) row.get("score")).doubleValue());
            
            // 解析 metadata JSON 或使用字符串解析
            String metadata = (String) row.get("metadata");
            // TODO: 解析 metadata 提取 documentName, chunkIndex 等
            cr.setDocumentName("未知");
            
            results.add(cr);
        }
        return results;
    }
}
