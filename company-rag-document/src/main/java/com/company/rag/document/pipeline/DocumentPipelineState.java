package com.company.rag.document.pipeline;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文档入库管道任务状态实体（映射 document_pipeline_state）。
 *
 * <p>每租户 schema 独立一张，承载分步状态机进度与失败信息。</p>
 */
@Data
@TableName("document_pipeline_state")
public class DocumentPipelineState {

    @TableId(type = IdType.INPUT)
    private UUID taskId;
    private Long documentId;
    private Long tenantId;
    private String step;          // 当前（或最近一个）步骤名
    private String status;        // PipelineStatus.name()
    private String errorStep;
    private String errorMsg;
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}