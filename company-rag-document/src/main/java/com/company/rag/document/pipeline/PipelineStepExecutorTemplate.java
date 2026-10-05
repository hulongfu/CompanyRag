package com.company.rag.document.pipeline;

import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.context.TenantContextSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 文档入库管道执行模板（{@link DocumentPipelineProcessor} 的实现）。
 *
 * <p>编排四步骤，承担横切职责：</p>
 * <ul>
 *   <li>租户恢复：worker 线程执行前用任务携带的快照恢复 TenantContext（ThreadLocal + MDC）。</li>
 *   <li>fail-closed 双校验：ThreadLocal schema 与 {@code current_schema()} 必须等于期望 schema，防止越权写其他租户。</li>
 *   <li>状态机推进：每步独立落库，成功后推进 document_pipeline_state；全部成功置 SUCCESS。</li>
 *   <li>重试：切分/入库/向量化按策略失败重试；解析不重试。</li>
 *   <li>删除竞态：捕获 {@link PipelineNotFoundAfterDelete} 后清理状态记录并终止，不置 FAILED。</li>
 *   <li>边界清理：finally 中清理当前线程的 TenantContext。</li>
 * </ul>
 */
@Slf4j
@Component
public class PipelineStepExecutorTemplate implements DocumentPipelineProcessor {

    private final DocumentPipelineStateMapper stateMapper;
    private final DocumentPipelineRecover recover;
    private final JdbcTemplate jdbcTemplate;
    private final DocumentPipelineStep parseStep;
    private final DocumentPipelineStep chunkStep;
    private final DocumentPipelineStep ingestStep;
    private final DocumentPipelineStep vectorizeStep;

    public PipelineStepExecutorTemplate(DocumentPipelineStateMapper stateMapper,
                                        DocumentPipelineRecover recover,
                                        JdbcTemplate jdbcTemplate,
                                        ParseStepFileRef parseStep,
                                        ChunkStep chunkStep,
                                        IngestStep ingestStep,
                                        VectorizeStep vectorizeStep) {
        this.stateMapper = stateMapper;
        this.recover = recover;
        this.jdbcTemplate = jdbcTemplate;
        this.parseStep = parseStep;
        this.chunkStep = chunkStep;
        this.ingestStep = ingestStep;
        this.vectorizeStep = vectorizeStep;
    }

    /**
     * 步骤定义及进入各步骤时应设置的状态（步骤执行前即推进状态）。
     * 顺序与状态机对应：PARSING → CHUNKING → RAG_INGEST → VECTORIZING → SUCCESS。
     */
    private static class Step {
        final DocumentPipelineStep step;
        final PipelineStatus status;

        Step(DocumentPipelineStep step, PipelineStatus status) {
            this.step = step;
            this.status = status;
        }
    }

    @Override
    public void processTask(PipelineTask task) {
        UUID taskId = task.getTaskId();
        TenantContextSnapshot snapshot = task.getTenantSnapshot();
        log.info("开始执行文档管道任务 | taskId={} | documentId={} | schema={}",
                taskId, task.getDocumentId(), task.getExpectedSchema());

        // 每个步骤的编排（含进入该步时要落库的状态）
        List<Step> flow = List.of(
                new Step(parseStep, PipelineStatus.PARSING),
                new Step(chunkStep, PipelineStatus.CHUNKING),
                new Step(ingestStep, PipelineStatus.RAG_INGEST),
                new Step(vectorizeStep, PipelineStatus.VECTORIZING)
        );

        String lastStep = null;

        try {
            if (snapshot == null) {
                throw new IllegalStateException("任务缺少租户上下文快照");
            }
            snapshot.apply();
            assertFailClosed(task);

            // 确保状态记录存在（首次为 PENDING）；sync 语义下上传请求已插入，此处幂等兜底
            ensureState(task);

            for (Step flowStep : flow) {
                // 进入该步：先落库当前状态，再执行（失败时可定位到步骤）
                lastStep = flowStep.step.name();
                pushState(task, taskId, flowStep.status, lastStep);

                boolean vectorize = flowStep.step == vectorizeStep;
                if (flowStep.step == parseStep) {
                    // 解析不重试
                    flowStep.step.execute(task);
                } else {
                    recover.executeWithRetry(flowStep.step.name(), vectorize,
                            () -> { flowStep.step.execute(task); return null; });
                }
            }

            // 全部成功 → 终态 SUCCESS
            success(task, taskId);
        } catch (PipelineNotFoundAfterDelete e) {
            // 文档已被删除：清理管道状态记录，不标记失败（避免 FAILED 残留误导）
            log.warn("文档已删除，清理管道状态 | taskId={} | reason={}", taskId, e.getMessage());
            stateMapper.deleteById(taskId);
        } catch (Exception e) {
            log.error("文档管道任务失败 | taskId={} | documentId={} | error={}",
                    taskId, task.getDocumentId(), e.getMessage(), e);
            fail(task, taskId, lastStep, e);
        } finally {
            TenantContext.clear();
        }
    }

    // ---------------- 横切工具方法 ----------------

    /**
     * fail-closed：ThreadLocal 记录的 schema 与数据库 {@code current_schema()} 都必须等于任务期望 schema。
     * 任一不符立即抛异常终止，防止在错误租户下写数据。
     */
    private void assertFailClosed(PipelineTask task) {
        String threadSchema = TenantContext.getSchema();
        if (threadSchema == null || !threadSchema.equals(task.getExpectedSchema())) {
            throw new IllegalStateException(
                    "租户上下文 schema 不符 | expected=" + task.getExpectedSchema() + " | actual=" + threadSchema);
        }
        String dbSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
        if (dbSchema == null || !dbSchema.equals(task.getExpectedSchema())) {
            throw new IllegalStateException(
                    "数据库 current_schema() 不符 | expected=" + task.getExpectedSchema() + " | actual=" + dbSchema);
        }
    }

    private void ensureState(PipelineTask task) {
        if (stateMapper.selectById(task.getTaskId()) == null) {
            DocumentPipelineState s = new DocumentPipelineState();
            s.setTaskId(task.getTaskId());
            s.setDocumentId(task.getDocumentId());
            s.setTenantId(task.getTenantId());
            s.setStep(parseStep.name());
            s.setStatus(PipelineStatus.PENDING.name());
            s.setRetryCount(0);
            s.setCreateTime(LocalDateTime.now());
            s.setUpdateTime(LocalDateTime.now());
            stateMapper.insert(s);
        }
    }

    private void pushState(PipelineTask task, UUID taskId, PipelineStatus status, String step) {
        DocumentPipelineState s = new DocumentPipelineState();
        s.setTaskId(taskId);
        s.setDocumentId(task.getDocumentId());
        s.setTenantId(task.getTenantId());
        s.setStep(step);
        s.setStatus(status.name());
        s.setRetryCount(0);
        s.setUpdateTime(LocalDateTime.now());
        // 按主键精确更新（避免整表/其他行误更）
        stateMapper.updateById(s);
    }

    private void success(PipelineTask task, UUID taskId) {
        DocumentPipelineState s = new DocumentPipelineState();
        s.setTaskId(taskId);
        s.setDocumentId(task.getDocumentId());
        s.setTenantId(task.getTenantId());
        s.setStep(vectorizeStep.name());
        s.setStatus(PipelineStatus.SUCCESS.name());
        s.setRetryCount(0);
        s.setUpdateTime(LocalDateTime.now());
        stateMapper.updateById(s);
    }

    private void fail(PipelineTask task, UUID taskId, String lastStep, Exception e) {
        DocumentPipelineState s = new DocumentPipelineState();
        s.setTaskId(taskId);
        s.setDocumentId(task.getDocumentId());
        s.setTenantId(task.getTenantId());
        s.setStatus(PipelineStatus.FAILED.name());
        s.setStep(lastStep);
        s.setErrorStep(lastStep);
        s.setErrorMsg(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        s.setUpdateTime(LocalDateTime.now());
        stateMapper.updateById(s);
    }
}