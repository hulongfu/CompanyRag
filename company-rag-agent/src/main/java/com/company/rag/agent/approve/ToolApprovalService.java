package com.company.rag.agent.approve;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
 * <p>
 * 幂等/并发：approve/deny 采用【原子条件更新】（WHERE id=? AND status=PENDING）并依据
 * 影响行数判定成败。PENDING 是唯一可决策状态，同时只有一个操作能成功，杜绝并发覆盖与
 * 重复决策（read-modify-write 竞态）。
 */
@Slf4j
@Service
public class ToolApprovalService {

    /** 审批超时（含收敛器）统一拒绝文案，避免多处漂移 */
    private static final String TIMEOUT_DENY_MSG = "审批超时";

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
                return ApprovalVerdict.deny("审批单不存在");
            }
            if (ToolApprovalStatus.EXECUTED.equals(row.getStatus())) {
                return ApprovalVerdict.proceed();
            }
            if (ToolApprovalStatus.DENIED.equals(row.getStatus())) {
                return ApprovalVerdict.deny(row.getResult());
            }
            try {
                Thread.sleep(props.getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ApprovalVerdict.deny("等待被中断");
            }
        }
        // 超时：直接收敛为 DENIED
        String msg = timeoutMessage(props.getTimeoutSeconds());
        deny(requestId, msg);
        return ApprovalVerdict.deny(msg);
    }

    /**
     * 批准：基于状态机的原子条件更新。仅 PENDING 单能成功，天然幂等且防并发覆盖。
     *
     * @return true 表示本次调用成功把它从 PENDING 更新为 EXECUTED
     */
    public boolean approve(Long id) {
        LambdaUpdateWrapper<ToolApprovalRequest> wrapper = new LambdaUpdateWrapper<ToolApprovalRequest>()
                .eq(ToolApprovalRequest::getId, id)
                .eq(ToolApprovalRequest::getStatus, ToolApprovalStatus.PENDING);
        ToolApprovalRequest update = new ToolApprovalRequest();
        update.setStatus(ToolApprovalStatus.EXECUTED);
        update.setDecidedAt(LocalDateTime.now());
        int rows = mapper.update(update, wrapper);
        if (rows > 0) {
            log.info("[APPROVAL] 已批准 id={}", id);
            return true;
        }
        log.warn("[APPROVAL] 单不存在或已被决策，忽略重复 approve id={}", id);
        return false;
    }

    /**
     * 拒绝：基于状态机的原子条件更新。仅 PENDING 单能成功，天然幂等且防并发覆盖。
     *
     * @return true 表示本次调用成功把它从 PENDING 更新为 DENIED
     */
    public boolean deny(Long id, String reason) {
        LambdaUpdateWrapper<ToolApprovalRequest> wrapper = new LambdaUpdateWrapper<ToolApprovalRequest>()
                .eq(ToolApprovalRequest::getId, id)
                .eq(ToolApprovalRequest::getStatus, ToolApprovalStatus.PENDING);
        ToolApprovalRequest update = new ToolApprovalRequest();
        update.setStatus(ToolApprovalStatus.DENIED);
        update.setResult(reason);
        update.setDecidedAt(LocalDateTime.now());
        int rows = mapper.update(update, wrapper);
        if (rows > 0) {
            log.info("[APPROVAL] 已拒绝 id={}, reason={}", id, reason);
            return true;
        }
        return false;
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

    /** 统一超时拒绝文案（附具体阈值秒数） */
    private String timeoutMessage(long seconds) {
        return TIMEOUT_DENY_MSG + "(" + seconds + "s)，已自动拒绝";
    }

    /** 审批等待裁决。 */
    public static final class ApprovalVerdict {
        private final boolean proceed;
        private final String denyMessage;

        private ApprovalVerdict(boolean proceed, String denyMessage) {
            this.proceed = proceed;
            this.denyMessage = denyMessage;
        }

        public static ApprovalVerdict proceed() {
            return new ApprovalVerdict(true, null);
        }

        public static ApprovalVerdict deny(String msg) {
            return new ApprovalVerdict(false, msg);
        }

        public boolean shouldProceed() {
            return proceed;
        }

        public String getDenyMessage() {
            return denyMessage;
        }
    }
}
