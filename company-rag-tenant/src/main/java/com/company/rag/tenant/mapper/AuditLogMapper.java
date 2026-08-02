package com.company.rag.tenant.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.rag.tenant.model.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
