package com.company.rag.rag.eval.answer;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 回答评估结果持久化实体（每租户 schema 一张）。
 * 三维分数平铺为三列，便于 SQL 统计；source 区分 online/manual。
 */
@Data
@TableName("answer_eval_result")
public class AnswerEvalResultEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** 关联 rag_session.id（在线评估来源，可空） */
    private Long sessionRowId;

    private String query;

    /** 评估时使用的检索上下文快照 */
    private String context;

    private String answer;

    /** 综合是否通过 */
    private Boolean pass;

    /** 综合评分 0~1 */
    private Double score;

    private Double relevancyScore;

    private Double correctnessScore;

    private Double faithfulnessScore;

    /** 来源：online / manual */
    private String source;

    private LocalDateTime createTime;
}
