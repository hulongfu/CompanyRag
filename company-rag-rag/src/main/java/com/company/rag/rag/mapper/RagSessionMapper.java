package com.company.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.rag.entity.RagSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 会话 Mapper 接口
 */
@Mapper
public interface RagSessionMapper extends BaseMapper<RagSession> {
}