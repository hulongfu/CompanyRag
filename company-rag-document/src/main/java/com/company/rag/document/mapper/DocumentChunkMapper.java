package com.company.rag.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.document.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {
}