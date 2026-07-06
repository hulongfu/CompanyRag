package com.company.rag.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档切分块
 */
@Data
@TableName("doc_chunk")
public class DocumentChunk {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Long tenantId;
    private Integer chunkIndex;     // 块序号
    private String content;         // 块文本内容
    private Integer tokenCount;     // Token数（用于成本统计）
    private String splitStrategy;   // 切分策略名称
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
