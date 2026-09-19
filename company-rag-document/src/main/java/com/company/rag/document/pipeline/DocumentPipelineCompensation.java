package com.company.rag.document.pipeline;

import com.company.rag.document.entity.Document;
import com.company.rag.document.mapper.DocumentMapper;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.context.TenantContextSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 崩溃补偿（系统启动时执行）。
 *
 * <p>扫描所有 {@code tenant_%} schema 中 document_pipeline_state 的非终态记录
 * （PENDING/PARSING/CHUNKING/RAG_INGEST/VECTORIZING），重建 {@link PipelineTask}
 * 后重新执行，从记录的 step 续跑。</p>
 *
 * <p>要点：</p>
 * <ul>
 *   <li>补偿线程同样恢复租户上下文（snapshot.of → apply → clear），避免补偿写错 schema。</li>
 *   <li>fileRef 取 rag_document.file_path（上传落盘时的临时文件路径）；文件已被清理的任务会在解析步骤失败置 FAILED。</li>
 *   <li>用本地固定线程池限制并发，避免启动时大量补偿任务同时压入线程池。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentPipelineCompensation implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final DocumentMapper documentMapper;
    private final DocumentPipelineProcessor processor;

    @Value("${document.pipeline.compensate-concurrency:2}")
    private int concurrency;

    @Override
    public void run(ApplicationArguments args) {
        log.info("启动文档管道崩溃补偿扫描");
        long started = System.currentTimeMillis();

        List<String> schemas = listTenantSchemas();
        int total = 0;
        for (String schema : schemas) {
            // 部分 tenant schema（如测试遗留 schema、V4 迁移后新建的 schema）可能尚无 document_pipeline_state 表，
            // 只对已建表的 schema 执行崩溃补偿，缺失表时跳过，避免整体启动失败。
            if (!hasPipelineTable(schema)) {
                log.info("跳过崩溃补偿 schema={}：无 document_pipeline_state 表", schema);
                continue;
            }
            List<PipelineTask> tasks = collectNonTerminalTasks(schema);
            total += tasks.size();
            executeWithConcurrency(tasks);
        }

        log.info("文档管道崩溃补偿完成 | schemas={} | tasks={} | costMs={}",
                schemas.size(), total, System.currentTimeMillis() - started);
    }

    private List<String> listTenantSchemas() {
        // 注意：信息模式扫描不允许参数化占位符在此重载下被绑定，故用转义字面量匹配（_ 为 LIKE 单字符通配需转义 \_）
        return jdbcTemplate.query(
                        "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'tenant\\_%'",
                        (rs, i) -> rs.getString(1))
                .stream()
                .filter(s -> s != null && s.startsWith("tenant_"))
                .toList();
    }

    /**
     * 判断指定 schema 是否已建 document_pipeline_state 表，避免对缺失该表的 schema 执行补偿导致启动失败。
     */
    private boolean hasPipelineTable(String schema) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = 'document_pipeline_state'",
                Integer.class, schema);
        return count != null && count > 0;
    }

    private List<PipelineTask> collectNonTerminalTasks(String schema) {
        String table = schema + ".document_pipeline_state";
        String sql = "SELECT task_id, document_id, tenant_id FROM " + table
                + " WHERE status NOT IN ('SUCCESS','FAILED')";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);

        return rows.stream().map(row -> {
            UUID taskId = (UUID) row.get("task_id");
            Long documentId = ((Number) row.get("document_id")).longValue();
            Long tenantId = ((Number) row.get("tenant_id")).longValue();

            String fileRef = null;
            // 读取 rag_document 的 file_path 作为临时文件引用（需先恢复到该租户 schema）
            TenantContextSnapshot snapshot = TenantContextSnapshot.of(tenantId, schema);
            snapshot.apply();
            try {
                Document doc = documentMapper.selectById(documentId);
                if (doc != null) {
                    fileRef = doc.getFilePath();
                }
            } finally {
                TenantContext.clear();
            }

            return PipelineTask.builder()
                    .taskId(taskId)
                    .documentId(documentId)
                    .tenantId(tenantId)
                    .expectedSchema(schema)
                    .fileRef(fileRef)
                    .tenantSnapshot(snapshot)
                    .build();
        }).toList();
    }

    /**
     * 以固定并发上限执行补偿任务，等待全部结束后返回（run() 同步语义，避免启动初始化时后台任务与补偿并发）。
     */
    private void executeWithConcurrency(List<PipelineTask> tasks) {
        if (tasks.isEmpty()) {
            return;
        }
        int threads = Math.max(1, Math.min(concurrency, tasks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (PipelineTask task : tasks) {
            pool.submit(() -> processor.processTask(task));
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.MINUTES)) {
                log.warn("补偿任务未在限期内完成 | tasks={}", tasks.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("补偿执行被中断", e);
        }
    }
}