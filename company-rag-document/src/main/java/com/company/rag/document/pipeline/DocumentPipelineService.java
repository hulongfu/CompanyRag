package com.company.rag.document.pipeline;

import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * 文档管道服务：上传落盘提交、状态查询、失败补跑。
 */
public interface DocumentPipelineService {

    /**
     * 上传文档：校验大小 → 落盘临时文件 → 建文档记录 → 写 PENDING 状态 → 提交异步管道 → 返回 taskId。
     *
     * @param file     上传文件
     * @param tenantId 租户 ID
     * @return 管道任务 ID
     */
    UUID submitUpload(MultipartFile file, Long tenantId);

    /**
     * 查询管道任务状态（跨租户不可见：非本租户 taskId 返回 null）。
     */
    PipelineStatusVO getStatus(UUID taskId, Long tenantId);

    /**
     * 失败补跑：仅 FAILED 状态可重跑，幂等重放整条管道（已成功的 chunk/向量跳过）。
     *
     * @throws IllegalStateException 非 FAILED 或不属于当前租户时
     */
    void retryStep(UUID taskId, Long tenantId);
}