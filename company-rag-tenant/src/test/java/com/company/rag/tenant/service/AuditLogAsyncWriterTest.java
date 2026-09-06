package com.company.rag.tenant.service;

import com.company.rag.common.model.AuditLogContext;
import com.company.rag.tenant.mapper.AuditLogMapper;
import com.company.rag.tenant.model.AuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

/**
 * AuditLogAsyncWriter 单测：覆盖批量落库、能力（容量上限）、单条失败降级、关闭前 flush 残留
 */
@ExtendWith(MockitoExtension.class)
class AuditLogAsyncWriterTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    private BlockingQueue<AuditLogContext> queue;
    private AuditLogAsyncWriter writer;

    @BeforeEach
    void setUp() {
        queue = new ArrayBlockingQueue<>(10); // 小容量便于背压测试
        writer = new AuditLogAsyncWriter(auditLogMapper, queue);
    }

    private AuditLogContext ctx(int i) {
        return AuditLogContext.builder()
                .actionType("EXECUTE_TOOL")
                .targetType("tool")
                .targetId("tool-" + i)
                .tenantId("t1")
                .userId(1L)
                .build();
    }

    @Test
    void flushPersistsQueuedAndClearsQueue() {
        writer.offer(ctx(1));
        writer.offer(ctx(2));
        writer.offer(ctx(3));

        writer.flush();

        verify(auditLogMapper, times(3)).insert(any(AuditLog.class));
        assertEquals(0, queue.size(), "flush 后队列应清空");
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper, times(3)).insert(captor.capture());
        assertEquals("tool-1", captor.getAllValues().get(0).getTargetId());
        assertEquals("t1", captor.getAllValues().get(0).getTenantId());
    }

    @Test
    void offerBackpressureDropsWhenFull() {
        for (int i = 0; i < 10; i++) {
            assertTrue(writer.offer(ctx(i)), "容量内入队应成功");
        }
        // 队列已满：offer 失败丢弃（返回 false），不抛
        assertFalse(writer.offer(ctx(99)));
        assertEquals(10, queue.size(), "队列大小不得超上限");
        // 背压丢弃后 flush 只落已入队的 10 条
        writer.flush();
        verify(auditLogMapper, times(10)).insert(any(AuditLog.class));
    }

    @Test
    void singleRecordFailureDoesNotBreakBatch() {
        writer.offer(ctx(1));
        writer.offer(ctx(2));
        // 第一条插入失败，第二条仍应尝试落库
        org.mockito.BDDMockito.given(auditLogMapper.insert(any(AuditLog.class)))
                .willThrow(new RuntimeException("db down"))
                .willReturn(1);

        writer.flush();

        verify(auditLogMapper, times(2)).insert(any(AuditLog.class));
        assertEquals(0, queue.size());
    }

    @Test
    void shutdownFlushesResidualQueue() {
        writer.offer(ctx(1));
        writer.offer(ctx(2));

        writer.shutdown();

        verify(auditLogMapper, times(2)).insert(any(AuditLog.class));
        assertEquals(0, queue.size());
    }
}