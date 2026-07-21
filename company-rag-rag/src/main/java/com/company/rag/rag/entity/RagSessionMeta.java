package com.company.rag.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.company.rag.rag.handler.PgJsonbTypeHandler;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /**
     * 会话标签，数据库类型为 jsonb
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private List<String> tags;

    /**
     * 扩展元数据，数据库类型为 jsonb
     */
    @TableField(typeHandler = PgJsonbTypeHandler.class)
    private Map<String, Object> metadata;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}