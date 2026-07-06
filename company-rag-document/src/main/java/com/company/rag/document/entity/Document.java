package com.company.rag.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档实体
 */
@Data
@TableName("rag_document")
public class Document {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String fileName;
    private String fileType;       // pdf / docx / txt / md / html
    private Long fileSize;
    private String filePath;       // 存储路径
    private String title;
    private Integer chunkCount;    // 切分后的块数
    private Integer status;        // 0-待处理 1-处理中 2-已完成 3-失败
    private String errorMsg;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
