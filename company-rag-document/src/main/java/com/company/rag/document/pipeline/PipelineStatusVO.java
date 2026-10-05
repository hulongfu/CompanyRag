package com.company.rag.document.pipeline;

import lombok.Data;

/**
 * 管道任务状态视图（下行给前端轮询）。
 */
@Data
public class PipelineStatusVO {
    private String status;
    private String step;
    private String errorStep;
    private String errorMsg;
    private Integer retryCount;
}