package com.company.rag.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.rag.entity.RagSessionMeta;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * RAG 会话元信息 Mapper 接口
 */
@Mapper
public interface RagSessionMetaMapper extends BaseMapper<RagSessionMeta> {

    /**
     * 查询会话列表（分页）
     */
    List<RagSessionMeta> selectSessionList(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("tags") List<String> tags,
            @Param("offset") Integer offset,
            @Param("limit") Integer limit
    );

    /**
     * 统计会话数量
     */
    Long countSessionList(
            @Param("tenantId") Long tenantId,
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("tags") List<String> tags
    );
}