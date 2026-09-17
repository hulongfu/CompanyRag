package com.company.rag.rag.eval.answer;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 回答评估结果 Mapper（按租户 schema 隔离，RLS 兜底）。
 */
@Mapper
public interface AnswerEvalResultMapper extends BaseMapper<AnswerEvalResultEntity> {
}
