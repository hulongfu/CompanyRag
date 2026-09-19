package com.company.rag.agent.approve;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 工具审批单持久化实体（每租户 schema 一张，RLS 按 tenant_id + FORCE ROW LEVEL SECURITY 隔离）。
 * 状态机见 {@link ToolApprovalStatus}：PENDING → EXECUTED / DENIED。
 */
@Data
@TableName("tool_approval_request")
public class ToolApprovalRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** RLS 依据，取自调用线程 TenantContext（铁律：不新起租户上下文） */
    private Long tenantId;

    /** 工具名，如 execute */
    private String toolName;

    /** 调用参数快照（审批人复核用，如命令原文） */
    private String argsJson;

    /** 关联会话 ID */
    private String sessionId;

    /** 发起调用的用户（调用线程 TenantContext） */
    private Long requesterUserId;

    /** PENDING / EXECUTED / DENIED */
    private String status;

    /** approve 后回填执行结果；deny 后回填拒绝原因 */
    private String result;

    private LocalDateTime requestedAt;

    private LocalDateTime decidedAt;
}