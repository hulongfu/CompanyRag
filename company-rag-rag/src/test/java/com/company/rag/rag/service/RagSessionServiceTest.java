package com.company.rag.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.entity.RagSessionMeta;
import com.company.rag.rag.mapper.RagSessionMapper;
import com.company.rag.rag.mapper.RagSessionMetaMapper;
import com.company.rag.rag.service.impl.RagSessionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagSessionServiceImpl 单元测试
 */
@ExtendWith(MockitoExtension.class)
class RagSessionServiceTest {

    @Mock
    private RagSessionMapper sessionMapper;

    @Mock
    private RagSessionMetaMapper sessionMetaMapper;

    private RagSessionServiceImpl sessionService;

    @Captor
    private ArgumentCaptor<RagSessionMeta> metaCaptor;

    @Captor
    private ArgumentCaptor<RagSession> sessionCaptor;

    @Captor
    private ArgumentCaptor<LambdaQueryWrapper<RagSessionMeta>> metaQueryCaptor;

    @Captor
    private ArgumentCaptor<LambdaQueryWrapper<RagSession>> sessionQueryCaptor;

    private static final Long TENANT_ID = 100L;
    private static final Long USER_ID = 200L;
    private static final String SESSION_ID = "test-session-id-12345";
    private static final String TITLE = "测试会话";

    @BeforeEach
    void setUp() {
        sessionService = new RagSessionServiceImpl(sessionMapper, sessionMetaMapper);
    }

    @Test
    void testCreateSession() {
        RagSessionMeta result = sessionService.createSession(TENANT_ID, USER_ID, TITLE);
        assertNotNull(result);
        assertNotNull(result.getSessionId());
        assertEquals(TENANT_ID, result.getTenantId());
        assertEquals(USER_ID, result.getUserId());
        assertEquals(TITLE, result.getTitle());
        assertEquals(0, result.getMessageCount());
        assertFalse(result.getIsDeleted());
        assertEquals("[]", result.getTags());
        assertEquals("{}", result.getMetadata());
        verify(sessionMetaMapper).insert(metaCaptor.capture());
        RagSessionMeta captured = metaCaptor.getValue();
        assertEquals(result.getSessionId(), captured.getSessionId());
        assertEquals(TENANT_ID, captured.getTenantId());
        assertEquals(USER_ID, captured.getUserId());
        assertEquals(TITLE, captured.getTitle());
        assertEquals(0, captured.getMessageCount());
        assertFalse(captured.getIsDeleted());
        assertEquals("[]", captured.getTags());
        assertEquals("{}", captured.getMetadata());
    }

    @Test
    void testSaveConversation() {
        RagSessionMeta existingMeta = createDefaultMeta();
        when(sessionMetaMapper.selectOne(any())).thenReturn(existingMeta);
        sessionService.saveConversation(TENANT_ID, SESSION_ID, USER_ID,
                "你好", "你好！有什么可以帮助你的？", "context-data",
                10, 20, 100);
        verify(sessionMetaMapper, times(1)).selectOne(any());
        verify(sessionMapper).insert(sessionCaptor.capture());
        RagSession captured = sessionCaptor.getValue();
        assertEquals(SESSION_ID, captured.getSessionId());
        assertEquals(TENANT_ID, captured.getTenantId());
        assertEquals(USER_ID, captured.getUserId());
        assertEquals("你好", captured.getQuery());
        assertEquals("你好！有什么可以帮助你的？", captured.getAnswer());
        assertEquals("context-data", captured.getContext());
        assertEquals(10, captured.getTokensInput());
        assertEquals(20, captured.getTokensOutput());
        assertEquals(100, captured.getLatencyMs());
    }

    @Test
    void testSaveConversation_defaultsNullTokens() {
        RagSessionMeta existingMeta = createDefaultMeta();
        when(sessionMetaMapper.selectOne(any())).thenReturn(existingMeta);
        sessionService.saveConversation(TENANT_ID, SESSION_ID, USER_ID,
                "你好", "你好！有什么可以帮助你的？", "context-data",
                null, null, null);
        verify(sessionMapper).insert(sessionCaptor.capture());
        RagSession captured = sessionCaptor.getValue();
        assertEquals(0, captured.getTokensInput());
        assertEquals(0, captured.getTokensOutput());
        assertEquals(0, captured.getLatencyMs());
    }

    @Test
    void testSaveConversation_autoCreateSession() {
        when(sessionMetaMapper.selectOne(any()))
                .thenReturn(null)
                .thenReturn(null);
        sessionService.saveConversation(TENANT_ID, SESSION_ID, USER_ID,
                "自动创建会话", "这是回答", "context",
                5, 10, 50);
        verify(sessionMetaMapper, atLeastOnce()).insert(metaCaptor.capture());
        RagSessionMeta createdMeta = metaCaptor.getValue();
        assertNotNull(createdMeta.getSessionId());
        assertEquals(TENANT_ID, createdMeta.getTenantId());
        assertEquals(USER_ID, createdMeta.getUserId());
        assertEquals("自动创建会话", createdMeta.getTitle());
        verify(sessionMapper).insert(sessionCaptor.capture());
        RagSession captured = sessionCaptor.getValue();
        assertEquals(SESSION_ID, captured.getSessionId());
        assertEquals("自动创建会话", captured.getQuery());
    }

    @Test
    void testGetSessionList() {
        String keyword = "测试";
        List<String> tags = Arrays.asList("tag1", "tag2");
        int page = 1;
        int size = 10;
        int offset = 0;
        RagSessionMeta meta1 = createDefaultMeta();
        RagSessionMeta meta2 = createDefaultMeta();
        meta2.setSessionId("session-2");
        meta2.setTitle("第二个会话");
        List<RagSessionMeta> mockRecords = Arrays.asList(meta1, meta2);
        Long mockTotal = 2L;
        when(sessionMetaMapper.selectSessionList(TENANT_ID, USER_ID, keyword, tags, offset, size))
                .thenReturn(mockRecords);
        when(sessionMetaMapper.countSessionList(TENANT_ID, USER_ID, keyword, tags))
                .thenReturn(mockTotal);
        Page<RagSessionMeta> result = sessionService.getSessionList(TENANT_ID, USER_ID, keyword, tags, page, size);
        assertNotNull(result);
        assertEquals(page, result.getCurrent());
        assertEquals(size, result.getSize());
        assertEquals(mockTotal, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals(meta1, result.getRecords().get(0));
        assertEquals(meta2, result.getRecords().get(1));
        verify(sessionMetaMapper).selectSessionList(TENANT_ID, USER_ID, keyword, tags, offset, size);
        verify(sessionMetaMapper).countSessionList(TENANT_ID, USER_ID, keyword, tags);
    }

    @Test
    void testGetSessionList_emptyResult() {
        when(sessionMetaMapper.selectSessionList(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(sessionMetaMapper.countSessionList(any(), any(), any(), any()))
                .thenReturn(0L);
        Page<RagSessionMeta> result = sessionService.getSessionList(TENANT_ID, USER_ID, null, null, 1, 20);
        assertNotNull(result);
        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    @Test
    void testGetSessionDetail() {
        RagSession session1 = new RagSession();
        session1.setSessionId(SESSION_ID);
        session1.setTenantId(TENANT_ID);
        session1.setQuery("第一条消息");
        session1.setCreateTime(LocalDateTime.now());
        RagSession session2 = new RagSession();
        session2.setSessionId(SESSION_ID);
        session2.setTenantId(TENANT_ID);
        session2.setQuery("第二条消息");
        session2.setCreateTime(LocalDateTime.now().plusMinutes(1));
        List<RagSession> mockSessions = Arrays.asList(session1, session2);
        when(sessionMapper.selectList(any())).thenReturn(mockSessions);
        List<RagSession> result = sessionService.getSessionDetail(TENANT_ID, SESSION_ID);
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("第一条消息", result.get(0).getQuery());
        assertEquals("第二条消息", result.get(1).getQuery());
        verify(sessionMapper).selectList(sessionQueryCaptor.capture());
        assertNotNull(sessionQueryCaptor.getValue());
    }

    @Test
    void testGetSessionDetail_empty() {
        when(sessionMapper.selectList(any())).thenReturn(Collections.emptyList());
        List<RagSession> result = sessionService.getSessionDetail(TENANT_ID, SESSION_ID);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testDeleteSession() {
        RagSessionMeta meta = createDefaultMeta();
        when(sessionMetaMapper.selectOne(any())).thenReturn(meta);
        sessionService.deleteSession(TENANT_ID, SESSION_ID);
        assertTrue(meta.getIsDeleted());
        assertNotNull(meta.getUpdateTime());
        verify(sessionMetaMapper).updateById(meta);
    }

    @Test
    void testDeleteSession_notFound() {
        when(sessionMetaMapper.selectOne(any())).thenReturn(null);
        sessionService.deleteSession(TENANT_ID, SESSION_ID);
        verify(sessionMetaMapper, never()).updateById(any(RagSessionMeta.class));
    }

    @Test
    void testUpdateSession() {
        RagSessionMeta meta = createDefaultMeta();
        when(sessionMetaMapper.selectOne(any())).thenReturn(meta);
        String newTitle = "更新后的标题";
        List<String> newTags = Arrays.asList("ai", "rag", "test");
        sessionService.updateSession(TENANT_ID, SESSION_ID, newTitle, newTags);
        assertEquals(newTitle, meta.getTitle());
        assertEquals(newTags.toString(), meta.getTags());
        assertNotNull(meta.getUpdateTime());
        verify(sessionMetaMapper).updateById(meta);
    }

    @Test
    void testUpdateSession_titleOnly() {
        RagSessionMeta meta = createDefaultMeta();
        meta.setTags("[old]");
        when(sessionMetaMapper.selectOne(any())).thenReturn(meta);
        sessionService.updateSession(TENANT_ID, SESSION_ID, "仅更新标题", null);
        assertEquals("仅更新标题", meta.getTitle());
        assertEquals("[old]", meta.getTags());
        assertNotNull(meta.getUpdateTime());
        verify(sessionMetaMapper).updateById(meta);
    }

    @Test
    void testUpdateSession_tagsOnly() {
        RagSessionMeta meta = createDefaultMeta();
        meta.setTitle("原标题");
        when(sessionMetaMapper.selectOne(any())).thenReturn(meta);
        List<String> newTags = Arrays.asList("new-tag");
        sessionService.updateSession(TENANT_ID, SESSION_ID, null, newTags);
        assertEquals("原标题", meta.getTitle());
        assertEquals(newTags.toString(), meta.getTags());
        assertNotNull(meta.getUpdateTime());
        verify(sessionMetaMapper).updateById(meta);
    }

    @Test
    void testUpdateSession_notFound() {
        when(sessionMetaMapper.selectOne(any())).thenReturn(null);
        sessionService.updateSession(TENANT_ID, SESSION_ID, "标题", Arrays.asList("tag"));
        verify(sessionMetaMapper, never()).updateById(any(RagSessionMeta.class));
    }

    private RagSessionMeta createDefaultMeta() {
        RagSessionMeta meta = new RagSessionMeta();
        meta.setId(1L);
        meta.setSessionId(SESSION_ID);
        meta.setTenantId(TENANT_ID);
        meta.setUserId(USER_ID);
        meta.setTitle(TITLE);
        meta.setMessageCount(0);
        meta.setIsDeleted(false);
        meta.setTags("[]");
        meta.setMetadata("{}");
        meta.setCreateTime(LocalDateTime.now());
        return meta;
    }
}
