package com.company.rag.agent.approve;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具审批单 Mapper（按租户 schema 隔离，RLS 兜底）。
 * 注意：agent 模块原先不在 {@code CompanyRagApplication} 的 MapperScan 范围内，
 * 需将本包 {@code com.company.rag.agent.approve} 加入 MapperScan 才能被装配。
 */
@Mapper
public interface ToolApprovalRequestMapper extends BaseMapper<ToolApprovalRequest> {
}