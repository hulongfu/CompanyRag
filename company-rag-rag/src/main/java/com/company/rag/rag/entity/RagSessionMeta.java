package com.company.rag.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * RAG 会话元信息实体
 */
@Data
@TableName("rag_session_meta")
public class RagSessionMeta {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private Long tenantId;

    private Long userId;

    private String title;

    private String lastQuery;

    private Integer messageCount;

    private Boolean isDeleted;

    private String tags;

    private String metadata;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}