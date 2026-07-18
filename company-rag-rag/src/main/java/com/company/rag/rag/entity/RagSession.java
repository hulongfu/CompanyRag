package com.company.rag.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * RAG 会话明细实体
 */
@Data
@TableName("rag_session")
public class RagSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private Long tenantId;

    private Long userId;

    private String query;

    private String answer;

    private String context;

    private Integer tokensInput;

    private Integer tokensOutput;

    private Integer latencyMs;

    private LocalDateTime createTime;
}