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

    /**
     * 将 rag_session 表的 user_id 列收紧为 NOT NULL
     * 
     * 应用层三条写入路径（ChatController / ChatRouter / RagSearchServiceImpl）
     * 均已保证 user_id 非空（null 时兜底用户 1），因此 DB 层应收紧约束以一致兜底。
     * 历史可能存在 NULL 行，需先回填为兜底用户 1，再 SET NOT NULL，否则 ALTER 会失败。
     */
    @Bean
    public ApplicationRunner migrateRagSessionUserIdNotNull() {
        return args -> {
            log.info("开始执行 rag_session 表 user_id NOT NULL 迁移...");

            try {
                List<String> tenantSchemas = jdbcTemplate.queryForList(
                        "SELECT schema_name FROM information_schema.schemata " +
                        "WHERE schema_name LIKE 'tenant_%'",
                        String.class
                );

                int migratedCount = 0;
                int skippedCount = 0;

                for (String schemaName : tenantSchemas) {
                    try {
                        // 检查 user_id 是否已是 NOT NULL
                        Boolean isNotNull = jdbcTemplate.queryForObject(
                                "SELECT is_nullable = 'NO' FROM information_schema.columns " +
                                "WHERE table_schema = ? AND table_name = 'rag_session' AND column_name = 'user_id'",
                                Boolean.class,
                                schemaName
                        );

                        if (Boolean.TRUE.equals(isNotNull)) {
                            log.debug("Schema [{}] 的 rag_session.user_id 已是 NOT NULL，跳过", schemaName);
                            skippedCount++;
                            continue;
                        }

                        // 回填历史 NULL 行（与代码兜底逻辑一致，使用用户 1）
                        String backfillSql = String.format(
                                "UPDATE %s.rag_session SET user_id = 1 WHERE user_id IS NULL",
                                schemaName
                        );
                        jdbcTemplate.execute(backfillSql);

                        // 收紧为 NOT NULL
                        String alterSql = String.format(
                                "ALTER TABLE %s.rag_session ALTER COLUMN user_id SET NOT NULL",
                                schemaName
                        );
                        jdbcTemplate.execute(alterSql);

                        log.info("Schema [{}] 的 rag_session.user_id 已收紧为 NOT NULL", schemaName);
                        migratedCount++;

                    } catch (Exception e) {
                        log.error("Schema [{}] 的 user_id NOT NULL 迁移失败：{}", schemaName, e.getMessage());
                        // 继续处理下一个 schema，不中断整体迁移
                    }
                }

                log.info("rag_session.user_id NOT NULL 迁移完成：成功 {} 个，跳过 {} 个", migratedCount, skippedCount);

            } catch (Exception e) {
                log.error("rag_session.user_id NOT NULL 迁移失败：{}", e.getMessage(), e);
                // 不抛出异常，避免启动失败
            }
        };
    }
}
