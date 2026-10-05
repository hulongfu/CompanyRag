package com.company.rag.rag.memory;

import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.service.RagSessionService;
import com.company.rag.tenant.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RagChatMemory} 单元测试 —— 覆盖正常 / 边界 / 异常场景。
 *
 * <p>验证：身份恒取 TenantContext（非伪造 body）、limit 换算（一轮=2 行）、
 * 条件不足返回空列表、RagSession→Message 转换且保持升序。使用 Mockito mock service。</p>
 */
class RagChatMemoryTest {

    private RagSessionService ragSessionService;
    private RagChatMemory ragChatMemory;

    @BeforeEach
    void setUp() {
        ragSessionService = mock(RagSessionService.class);
        ragChatMemory = new RagChatMemory(ragSessionService);
        ReflectionTestUtils.setField(ragChatMemory, "windowSize", 50);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RagSession session(String query, String answer) {
        RagSession s = new RagSession();
        s.setQuery(query);
        s.setAnswer(answer);
        return s;
    }

    @Test
    void 身份恒取TenantContext且limit换算_round50限100() {
        TenantContext.setTenantId(10L);
        TenantContext.setUserId(20L);
        when(ragSessionService.getRecentSessionDetail(anyLong(), anyLong(), anyString(), eq(100)))
                .thenReturn(List.of());

        ragChatMemory.get("session-1");

        ArgumentCaptor<Long> tenant = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> user = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> sid = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(ragSessionService).getRecentSessionDetail(tenant.capture(), user.capture(), sid.capture(), limit.capture());
        assertEquals(10L, tenant.getValue());
        assertEquals(20L, user.getValue());
        assertEquals("session-1", sid.getValue());
        assertEquals(100, limit.getValue());
    }

    @Test
    void window为负_全量退化limit传0() {
        ReflectionTestUtils.setField(ragChatMemory, "windowSize", -1);
        TenantContext.setTenantId(10L);
        TenantContext.setUserId(20L);
        when(ragSessionService.getRecentSessionDetail(anyLong(), anyLong(), anyString(), eq(0)))
                .thenReturn(List.of());

        ragChatMemory.get("session-1");

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(ragSessionService).getRecentSessionDetail(anyLong(), anyLong(), anyString(), limit.capture());
        assertEquals(0, limit.getValue());
    }

    @Test
    void tenant未设置_返回空且不调用service() {
        TenantContext.setUserId(20L);
        // 不设置 tenantId

        List<Message> messages = ragChatMemory.get("session-1");

        assertTrue(messages.isEmpty());
        verify(ragSessionService, never()).getRecentSessionDetail(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void sessionId为null_返回空且不调用service() {
        TenantContext.setTenantId(10L);
        TenantContext.setUserId(20L);

        List<Message> messages = ragChatMemory.get(null);

        assertTrue(messages.isEmpty());
        verify(ragSessionService, never()).getRecentSessionDetail(anyLong(), anyLong(), anyString(), anyInt());
    }

    @Test
    void 转换正确_保持升序() {
        TenantContext.setTenantId(10L);
        TenantContext.setUserId(20L);
        List<RagSession> sessions = new ArrayList<>();
        sessions.add(session("q1", "a1"));
        sessions.add(session("q2", "a2"));
        when(ragSessionService.getRecentSessionDetail(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(sessions);

        List<Message> messages = ragChatMemory.get("session-1");

        assertNotNull(messages);
        assertEquals(4, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage);
        assertEquals("q1", ((UserMessage) messages.get(0)).getText());
        assertTrue(messages.get(1) instanceof AssistantMessage);
        assertEquals("a1", ((AssistantMessage) messages.get(1)).getText());
        assertTrue(messages.get(2) instanceof UserMessage);
        assertEquals("q2", ((UserMessage) messages.get(2)).getText());
        assertTrue(messages.get(3) instanceof AssistantMessage);
        assertEquals("a2", ((AssistantMessage) messages.get(3)).getText());
    }

    @Test
    void service返回null_返回空列表() {
        TenantContext.setTenantId(10L);
        TenantContext.setUserId(20L);
        when(ragSessionService.getRecentSessionDetail(anyLong(), anyLong(), anyString(), anyInt()))
                .thenReturn(null);

        List<Message> messages = ragChatMemory.get("session-1");

        assertTrue(messages.isEmpty());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}