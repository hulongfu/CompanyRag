package com.company.rag.document.pipeline;

import com.company.rag.tenant.context.TenantContextSnapshot;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * 文档入库管道任务载荷。
 *
 * <p>上传请求线程构造后提交给 {@link AsyncDocumentPipelineExecutor}，随后在 worker 线程执行。
 * {@code tenantSnapshot} 在上传请求线程 {@code captureNow()} 捕获，随任务传递，供每个步骤执行前恢复租户上下文。</p>
 */
@Data
@Builder
public class PipelineTask {

    private final UUID taskId;
    private final Long documentId;
    private final Long tenantId;
    /** 期望落库的租户 schema 名（fail-closed 断言用）。 */
    private final String expectedSchema;
    /** 已落盘的临时文件绝对路径（解析步骤读取）。 */
    private final String fileRef;
    private final String fileName;
    /** 上传请求线程捕获的租户上下文快照。 */
    private final TenantContextSnapshot tenantSnapshot;
}