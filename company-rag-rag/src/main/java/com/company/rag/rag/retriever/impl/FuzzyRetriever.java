package com.company.rag.rag.retriever.impl;

import com.company.rag.rag.model.RagResult;
import com.company.rag.tenant.context.TenantSqlHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模糊匹配器（PostgreSQL pg_trgm）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FuzzyRetriever {
    
    private final JdbcTemplate jdbcTemplate;
    
    /**
     * 模糊匹配
     * @param query 查询文本
     * @param topK 返回数量
     * @return 检索结果
     */
    public List<RagResult.ChunkResult> retrieve(String query, int topK) {
        // 获取租户 schema 并拼接表名，确保租户隔离
        String schema = TenantSqlHelper.requireSchema();
        String table = TenantSqlHelper.getQualifiedTableName(schema, "vector_store");
        
        String sql = "SELECT id, content, metadata, " +
                "similarity(content, ?) AS score " +
                "FROM " + table + " " +
                "WHERE similarity(content, ?) > 0.1 " +
                "ORDER BY score DESC " +
                "LIMIT ?";
        
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, query, query, topK);
        return convertToChunkResults(rows);
    }
    
    private List<RagResult.ChunkResult> convertToChunkResults(List<Map<String, Object>> rows) {
        List<RagResult.ChunkResult> results = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            RagResult.ChunkResult cr = new RagResult.ChunkResult();
            cr.setChunkId(row.get("id") != null ? row.get("id").toString() : "");
            cr.setContent((String) row.get("content"));
            cr.setKeywordScore(((Number) row.get("score")).doubleValue());
            
            // 从 metadata 中提取 documentName 和 chunkIndex
            Object metadataObj = row.get("metadata");
            if (metadataObj instanceof org.postgresql.util.PGobject) {
                try {
                    String metadataJson = ((org.postgresql.util.PGobject) metadataObj).getValue();
                    com.fasterxml.jackson.databind.JsonNode jsonNode = 
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(metadataJson);
                    
                    // 提取 documentName
                    if (jsonNode.has("documentName")) {
                        cr.setDocumentName(jsonNode.get("documentName").asText());
                    } else {
                        cr.setDocumentName("未知");
                    }
                    
                    // 提取 chunkIndex
                    if (jsonNode.has("chunkIndex")) {
                        cr.setChunkIndex(jsonNode.get("chunkIndex").asInt());
                    }
                    
                    // 提取 documentId
                    if (jsonNode.has("documentId")) {
                        cr.setDocumentId(jsonNode.get("documentId").asLong());
                    }
                } catch (Exception e) {
                    log.warn("解析 metadata JSON 失败：{}", e.getMessage());
                    cr.setDocumentName("未知");
                }
            } else if (metadataObj != null) {
                // 非 PGobject 情况（理论上不会发生，但做防御性处理）
                cr.setDocumentName("未知");
            } else {
                cr.setDocumentName("未知");
            }
            
            results.add(cr);
        }
        return results;
    }
}
