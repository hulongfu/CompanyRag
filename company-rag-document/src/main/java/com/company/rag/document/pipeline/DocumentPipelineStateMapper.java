package com.company.rag.document.pipeline;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档入库管道状态表 Mapper。
 */
@Mapper
public interface DocumentPipelineStateMapper extends BaseMapper<DocumentPipelineState> {
}