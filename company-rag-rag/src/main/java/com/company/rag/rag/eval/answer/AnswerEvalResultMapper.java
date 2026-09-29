package com.company.rag.rag.eval.answer;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 回答评估结果 Mapper（按租户 schema 隔离，RLS 兜底）。
 */
@Mapper
public interface AnswerEvalResultMapper extends BaseMapper<AnswerEvalResultEntity> {

    /**
     * 抽取带人工标签（feedback≠0）的评估样本集（spec §3.2.2 dataset 口径）。
     *
     * 去重键 = session_row_id（非 query）：内层按会话取最新评估（e.id DESC 处理同秒多行），
     * 外层 ORDER BY t.create_time DESC LIMIT 取最新 n 会话，防数据集冻结在最早样本。
     * ${schema} 仅存 TenantContext.getSchema()（已过白名单），其余参数全部 #{} 绑定防注入。
     */
    @Select("""
        SELECT * FROM (
          SELECT DISTINCT ON (e.session_row_id)
            e.query, e.context, e.answer, e.pass AS persisted_pass, e.score AS persisted_score,
            s.feedback AS human_label, e.tenant_id AS tenant_id,
            e.session_row_id, e.id AS eval_id, e.create_time
          FROM ${schema}.answer_eval_result e
          JOIN ${schema}.rag_session s ON s.id = e.session_row_id
          WHERE e.tenant_id = #{tenantId}
            AND s.tenant_id = #{tenantId}
            AND e.session_row_id IS NOT NULL
            AND s.feedback <> 0
            AND e.create_time BETWEEN #{from} AND #{to}
          ORDER BY e.session_row_id, e.id DESC
        ) t
        ORDER BY t.create_time DESC
        LIMIT #{limit}
        """)
    List<LabelledEvalSample> selectDataset(@Param("schema") String schema,
                                           @Param("tenantId") Long tenantId,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           @Param("limit") int limit);
}
