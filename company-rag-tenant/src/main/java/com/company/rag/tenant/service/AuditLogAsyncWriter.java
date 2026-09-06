package com.company.rag.tenant.service;

import com.company.rag.common.model.AuditLogContext;
import com.company.rag.tenant.mapper.AuditLogMapper;
import com.company.rag.tenant.model.AuditLog;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 审计日志异步批量落库器
 * <p>
 * 有界队列（默认 1000 条）+ 后台定时批量落库：每 500ms 或每满 100 条 drain 一批。
 * 队列满时 {@code offer} 失败丢弃 + log.warn（背压保护，绝不阻塞主流程）。
 * 应用关闭时 {@link #shutdown()} flush 队列残留。
 * <p>
 * 实现说明：项目未开启 {@code @EnableScheduling}，故用自建 {@link ScheduledExecutorService}，
 * 避免 {@code @Scheduled} 静默失效。
 */
@Slf4j
@Component
public class AuditLogAsyncWriter {

    private static final int QUEUE_CAPACITY = 1000;
    private static final int BATCH_SIZE = 100;
    private static final long FLUSH_INTERVAL_MS = 500;

    private final AuditLogMapper auditLogMapper;
    private final BlockingQueue<AuditLogContext> queue;
    private final ScheduledExecutorService scheduler;

    /**
     * Spring 构造：自建队列与后台调度线程，启动周期 flush。
     * 类存在 2 个构造器，须显式 {@code @Autowired} 指明 Spring 注入用哪个构造器，
     * 否则多构造器且无标注时 Spring 裸走无参构造 → 抛 NoSuchMethodException。
     */
    @Autowired
    public AuditLogAsyncWriter(AuditLogMapper auditLogMapper) {
        this(auditLogMapper, new ArrayBlockingQueue<>(QUEUE_CAPACITY));
        // 仅生产路径启动后台周期 flush；测试构造函数不调度，由测试手动触发
        this.scheduler.scheduleWithFixedDelay(this::flush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 供单测注入受控队列（不自动调度，由测试手动触发 flush），避免周期任务干扰断言。
     */
    AuditLogAsyncWriter(AuditLogMapper auditLogMapper, BlockingQueue<AuditLogContext> queue) {
        this.auditLogMapper = auditLogMapper;
        this.queue = queue;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "audit-log-writer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 入队。队列满返回 false（丢弃），背压保护。
     */
    public boolean offer(AuditLogContext ctx) {
        if (!queue.offer(ctx)) {
            log.warn("审计异步队列已满，丢弃记录：action={}", ctx.getActionType());
            return false;
        }
        return true;
    }

    /**
     * drain 至多 {@link #BATCH_SIZE} 条，逐条 insert 落库（非 SQL 级批量）。
     * 队列量不大时性能足够；如未来吞吐上来，可换成 {@code saveBatch} 提升写入效率。
     * 单条失败仅记录，不抛。
     */
    synchronized void flush() {
        List<AuditLogContext> batch = new ArrayList<>(BATCH_SIZE);
        queue.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        for (AuditLogContext ctx : batch) {
            try {
                auditLogMapper.insert(toEntity(ctx));
            } catch (Exception e) {
                log.error("审计异步落库失败：action={}", ctx.getActionType(), e);
            }
        }
    }

    private AuditLog toEntity(AuditLogContext ctx) {
        AuditLog log = new AuditLog();
        log.setTenantId(ctx.getTenantId());
        log.setUserId(ctx.getUserId());
        log.setActionType(ctx.getActionType());
        log.setTargetType(ctx.getTargetType());
        log.setTargetId(ctx.getTargetId());
        log.setDetail(ctx.getDetail());
        log.setIpAddress(ctx.getIpAddress());
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // flush 队列残留，避免关闭时丢审计
        flush();
    }
}