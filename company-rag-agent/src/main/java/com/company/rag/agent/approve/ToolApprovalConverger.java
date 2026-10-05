package com.company.rag.agent.approve;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.rag.agent.config.ApprovalProperties;
import com.company.rag.tenant.context.TenantContext;
import com.company.rag.tenant.mapper.TenantMapper;
import com.company.rag.tenant.model.Tenant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 审批超时收敛器：后台周期扫描各租户 schema 中超时的 PENDING 审批单，自动转 DENIED。
 * <p>
 * 项目未开启 @EnableScheduling，故仿 {@code AuditLogAsyncWriter} 自建单线程调度器。
 * 兜底目标：即使同步等待线程在超时后已转 DENIED，这里保证「会话外残留 PENDING」不成为孤儿。
 * <p>
 * 关键实现约束（已核实拦截器源码）：本器线程无租户上下文（scheduler 线程 schema=public），
 * 而 {@code TenantSchemaInterceptor}（TenantSchemaInterceptor L90）要求 schema 与 tenantId
 * 双非空才进入租户分支（SET search_path），{@code TenantLineHandler} 又取 tenantId 追加条件。
 * 因此必须【同时 setSchema + setTenantId】，只 setSchema 会落 public + tenant_id=null 致查不到。
 * 遍历时用 {@code public.sys_tenant}（ignoreTable 豁免，反查可靠）取 schemaName→tenantId。
 */
@Slf4j
@Component
public class ToolApprovalConverger {

    private final ToolApprovalRequestMapper mapper;
    private final ApprovalProperties props;
    private final TenantMapper tenantMapper;
    private final JdbcTemplate jdbcTemplate;

    private ScheduledExecutorService scheduler;

    public ToolApprovalConverger(ToolApprovalRequestMapper mapper,
                                 ApprovalProperties props,
                                 TenantMapper tenantMapper,
                                 JdbcTemplate jdbcTemplate) {
        this.mapper = mapper;
        this.props = props;
        this.tenantMapper = tenantMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void start() {
        if (!props.isEnabled()) {
            return; // 总开关关闭不启动
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tool-approval-converger");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::converge, 30, 30, TimeUnit.SECONDS);
        log.info("[APPROVAL] 超时收敛器启动：超时阈值 {}s", props.getTimeoutSeconds());
    }

    /**
     * 遍历所有租户 schema，把超时的 PENDING 单置为 DENIED。
     * 每个 schema：先经 public.sys_tenant 反查 tenantId（在未 set 上下文时查询，命中 public），
     * 再 setSchema + setTenantId 双上下文使 Mapper 命中正确租户 schema，finally clear。
     */
    void converge() {
        List<String> schemaNames;
        try {
            schemaNames = jdbcTemplate.queryForList(
                    "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'tenant_%'",
                    String.class);
        } catch (Exception e) {
            log.warn("[APPROVAL] 枚举租户 schema 失败（跳过本次收敛）：{}", e.getMessage());
            return;
        }

        for (String schema : schemaNames) {
            try {
                // 反查必须在未 set 上下文（public）时进行：sys_tenant 被 ignoreTable 豁免，不加 tenant 条件
                Tenant tenant = tenantMapper.selectOne(
                        new LambdaQueryWrapper<Tenant>().eq(Tenant::getSchemaName, schema));
                if (tenant == null || tenant.getId() == null) {
                    log.warn("[APPROVAL] schema {} 在 sys_tenant 无对应租户，跳过", schema);
                    continue;
                }
                Long tenantId = tenant.getId();

                // 双上下文（拦截器要求 schema+tenantId 双非空才走租户分支），try-finally clear
                TenantContext.setSchema(schema);
                TenantContext.setTenantId(tenantId);
                try {
                    LocalDateTime threshold = LocalDateTime.now().minusSeconds(props.getTimeoutSeconds());
                    List<ToolApprovalRequest> expired = mapper.selectList(
                            new LambdaQueryWrapper<ToolApprovalRequest>()
                                    .eq(ToolApprovalRequest::getStatus, ToolApprovalStatus.PENDING)
                                    .lt(ToolApprovalRequest::getRequestedAt, threshold));
                    for (ToolApprovalRequest row : expired) {
                        row.setStatus(ToolApprovalStatus.DENIED);
                        row.setResult("审批超时(" + props.getTimeoutSeconds() + "s)，已自动拒绝");
                        row.setDecidedAt(LocalDateTime.now());
                        mapper.updateById(row);
                        log.info("[APPROVAL] 超时转 DENIED id={}, tool={}, tenantId={}", row.getId(), row.getToolName(), tenantId);
                    }
                } finally {
                    TenantContext.clear();
                }
            } catch (Exception e) {
                log.warn("[APPROVAL] schema {} 收敛异常（跳过，不影响下次）：{}", schema, e.getMessage());
                TenantContext.clear(); // 确保上下文清理
            }
        }
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}