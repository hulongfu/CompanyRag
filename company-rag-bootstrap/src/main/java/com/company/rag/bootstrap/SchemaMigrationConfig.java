package com.company.rag.bootstrap;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 数据库 Schema 迁移配置
 * 
 * 用于在应用启动时自动执行必要的表结构变更（如添加新列）
 * 解决 Flyway 被 exclude 后无法执行迁移脚本的问题
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class SchemaMigrationConfig {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 为所有租户 schema 中的 rag_session 表添加 feedback 列
     * 
     * feedback 列用于记录用户对会话的反馈（👍/👎）
     * -1 = 👎, 0 = 未标记，1 = 👍
     */
    @Bean
    public ApplicationRunner migrateRagSessionFeedbackColumn() {
        return args -> {
            log.info("开始执行 rag_session 表 feedback 列迁移...");
            
            try {
                // 1. 查询所有租户 schema
                List<String> tenantSchemas = jdbcTemplate.queryForList(
                        "SELECT schema_name FROM information_schema.schemata " +
                        "WHERE schema_name LIKE 'tenant_%'",
                        String.class
                );
                
                log.info("发现 {} 个租户 schema: {}", tenantSchemas.size(), tenantSchemas);
                
                int migratedCount = 0;
                int skippedCount = 0;
                
                // 2. 遍历每个租户 schema，检查并添加 feedback 列
                for (String schemaName : tenantSchemas) {
                    try {
                        // 检查列是否已存在
                        Boolean columnExists = jdbcTemplate.queryForObject(
                                "SELECT EXISTS (" +
                                "  SELECT 1 FROM information_schema.columns " +
                                "  WHERE table_schema = ? AND table_name = 'rag_session' AND column_name = 'feedback'" +
                                ")",
                                Boolean.class,
                                schemaName
                        );
                        
                        if (columnExists != null && columnExists) {
                            log.debug("Schema [{}] 的 rag_session 表已存在 feedback 列，跳过", schemaName);
                            skippedCount++;
                            continue;
                        }
                        
                        // 添加 feedback 列（NOT NULL + DEFAULT 0，避免影响现有数据）
                        String alterSql = String.format(
                                "ALTER TABLE %s.rag_session ADD COLUMN feedback SMALLINT NOT NULL DEFAULT 0",
                                schemaName
                        );
                        jdbcTemplate.execute(alterSql);
                        
                        // 添加索引（如果不存在）
                        String createIndexSql = String.format(
                                "CREATE INDEX IF NOT EXISTS idx_%s_session_feedback ON %s.rag_session(feedback)",
                                schemaName, schemaName
                        );
                        jdbcTemplate.execute(createIndexSql);
                        
                        log.info("Schema [{}] 的 rag_session 表成功添加 feedback 列", schemaName);
                        migratedCount++;
                        
                    } catch (Exception e) {
                        log.error("Schema [{}] 的 feedback 列迁移失败：{}", schemaName, e.getMessage());
                        // 继续处理下一个 schema，不中断整体迁移
                    }
                }
                
                log.info("rag_session 表 feedback 列迁移完成：成功 {} 个，跳过 {} 个", migratedCount, skippedCount);
                
            } catch (Exception e) {
                log.error("rag_session 表 feedback 列迁移失败：{}", e.getMessage(), e);
                // 不抛出异常，避免启动失败（已有数据不影响核心功能）
            }
        };
    }
}
