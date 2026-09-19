package com.company.rag.agent.approve;

import com.company.rag.agent.config.ApprovalProperties;
import com.company.rag.agent.tool.AgentTool;
import com.company.rag.tenant.context.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 人类审批门服务（方案 A 同步等待）。
 * <p>
 * 设计：审批门是叠加的"人肉确认业务恰当性"层，不削弱/替代各工具既有硬校验（如
 * {@code ExecuteTool} 的命令白名单、{@code DatabaseQueryTool} 的只读 SQL 校验）。
 * 判定 = 工具 self-declared {@link AgentTool#requiresApproval()} OR 高危兜底集
 * （防个别工具漏声明导致默认放行）。
 * <p>
 * 出租户上下文处理约定：本服务不 set/clear TenantContext，沿用调用线程的上下文。
 * 方案 A 的落库/轮询/approve 后 execute 都在 agent 异步子线程内完成 —— 该线程已由
 * {@code caller} 手动 setSchema + setTenantId，命中正确租户 schema。
 */
@Slf4j
@Service
public class ToolApprovalService {

    private final ToolApprovalRequestMapper mapper;
    private final ApprovalProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolApprovalService(ToolApprovalRequestMapper mapper, ApprovalProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * 创建审批单（PENDING 落库）。租户上下文取自调用线程，不新起上下文（铁律）。
     */
    public ToolApprovalRequest createRequest(String toolName, Map<String, Object> params) {
        ToolApprovalRequest req = new ToolApprovalRequest();
        req.setTenantId(TenantContext.getTenantId());
        req.setToolName(toolName);
        req.setArgsJson(toJson(params));
        req.setSessionId(TenantContext.getSessionId());
        req.setRequesterUserId(TenantContext.getUserId());
        req.setStatus(ToolApprovalStatus.PENDING);
        req.setRequestedAt(LocalDateTime.now());
        mapper.insert(req);
        log.info("[APPROVAL] 待审批单创建 id={}, tool={}, tenantId={}", req.getId(), toolName, TenantContext.getTenantId());
        return req;
    }

    /**
     * 是否命中审批：总开关关闭一律 false；否则 高危兜底集 或 tool.requiresApproval()。
     */
    public boolean needsApproval(String toolName, AgentTool tool) {
        if (tool == null) {
            return false;
        }
        if (!props.isEnabled()) {
            return false;
        }
        return highRiskTools().contains(toolName) || tool.requiresApproval();
    }

    /**
     * 同步等待人工结果（在调用线程内轮询 DB）。返回是否放行执行。
     * 超时在等待线程内直接收敛为 DENIED（与会话外收敛器双保险）。
     */
    public ApprovalVerdict await(Long requestId, String toolName) {
        long deadline = System.currentTimeMillis() + props.getTimeoutSeconds() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            ToolApprovalRequest row = mapper.selectById(requestId);
            if (row == null) {
                log.warn("[APPROVAL] 审批单不存在 id={}", requestId);
                return ApprovalVerdict.deny("审批单不存在", false);
            }
            if (ToolApprovalStatus.EXECUTED.equals(row.getStatus())) {
                return ApprovalVerdict.proceed();
            }
            if (ToolApprovalStatus.DENIED.equals(row.getStatus())) {
                return ApprovalVerdict.deny(row.getResult(), true);
            }
            try {
                Thread.sleep(props.getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ApprovalVerdict.deny("等待被中断", false);
            }
        }
        // 超时：直接收敛为 DENIED
        markDenied(requestId, "审批超时(" + props.getTimeoutSeconds() + "s)，已自动拒绝");
        return ApprovalVerdict.deny("审批超时，已自动拒绝", false);
    }

    /**
     * approve：幂等，仅 PENDING 可决策。
     */
    public boolean approve(Long id) {
        ToolApprovalRequest row = mapper.selectById(id);
        if (row == null) {
            return false;
        }
        if (!ToolApprovalStatus.PENDING.equals(row.getStatus())) {
            log.warn("[APPROVAL] 已决策，忽略重复 approve id={}, status={}", id, row.getStatus());
            return false;
        }
        row.setStatus(ToolApprovalStatus.EXECUTED);
        row.setDecidedAt(LocalDateTime.now());
        mapper.updateById(row);
        log.info("[APPROVAL] 已批准 id={}, tool={}", id, row.getToolName());
        return true;
    }

    /**
     * deny：置 DENIED，可选原因。幂等。
     */
    public boolean deny(Long id, String reason) {
        ToolApprovalRequest row = mapper.selectById(id);
        if (row == null) {
            return false;
        }
        if (!ToolApprovalStatus.PENDING.equals(row.getStatus())) {
            return false;
        }
        row.setStatus(ToolApprovalStatus.DENIED);
        row.setResult(reason);
        row.setDecidedAt(LocalDateTime.now());
        mapper.updateById(row);
        log.info("[APPROVAL] 已拒绝 id={}, tool={}, reason={}", id, row.getToolName(), reason);
        return true;
    }

    private void markDenied(Long id, String reason) {
        deny(id, reason);
    }

    public long getTimeoutSeconds() {
        return props.getTimeoutSeconds();
    }

    private Set<String> highRiskTools() {
        if (props.getHighRiskTools() == null || props.getHighRiskTools().isBlank()) {
            return Set.of();
        }
        return Arrays.stream(props.getHighRiskTools().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private String toJson(Map<String, Object> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            log.warn("参数序列化失败，存原始 toString", e);
            return String.valueOf(params);
        }
    }

    /** 审批等待裁决。 */
    public static final class ApprovalVerdict {
        private final boolean proceed;
        @SuppressWarnings("unused")
        private final boolean deniedByUser;
        private final String denyMessage;

        private ApprovalVerdict(boolean proceed, boolean deniedByUser, String denyMessage) {
            this.proceed = proceed;
            this.deniedByUser = deniedByUser;
            this.denyMessage = denyMessage;
        }

        public static ApprovalVerdict proceed() {
            return new ApprovalVerdict(true, false, null);
        }

        public static ApprovalVerdict deny(String msg, boolean byUser) {
            return new ApprovalVerdict(false, byUser, msg);
        }

        public boolean shouldProceed() {
            return proceed;
        }

        public String getDenyMessage() {
            return denyMessage;
        }
    }
}