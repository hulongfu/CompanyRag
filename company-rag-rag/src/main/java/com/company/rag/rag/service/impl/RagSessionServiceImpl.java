package com.company.rag.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.entity.RagSessionMeta;
import com.company.rag.rag.mapper.RagSessionMapper;
import com.company.rag.rag.mapper.RagSessionMetaMapper;
import com.company.rag.rag.service.RagSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 会话服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagSessionServiceImpl implements RagSessionService {

    private final RagSessionMapper sessionMapper;
    private final RagSessionMetaMapper sessionMetaMapper;

    // 内存中的会话计数器（用于批量更新）
    private final Map<String, SessionCounter> sessionCounters = new ConcurrentHashMap<>();

    private static class SessionCounter {
        int count = 0;
        String lastQuery;
        LocalDateTime lastUpdateTime = LocalDateTime.now();

        synchronized void increment(String query) {
            count++;
            lastQuery = query;
            lastUpdateTime = LocalDateTime.now();
        }
    }

    @Override
    // @Transactional
    public RagSessionMeta createSession(Long tenantId, Long userId, String title) {
        String sessionId = UUID.randomUUID().toString().replace("-", "");

        RagSessionMeta meta = new RagSessionMeta();
        meta.setSessionId(sessionId);
        meta.setTenantId(tenantId);
        meta.setUserId(userId);
        meta.setTitle(title);
        meta.setMessageCount(0);
        meta.setIsDeleted(false);
        meta.setTags("[]");
        meta.setMetadata("{}");

        sessionMetaMapper.insert(meta);
        log.info("创建会话成功 | tenantId={} userId={} sessionId={} title={}",
                tenantId, userId, sessionId, title);

        return meta;
    }

    @Override
    public void saveConversation(Long tenantId, String sessionId, Long userId,
                                 String query, String answer, String context,
                                 Integer tokensInput, Integer tokensOutput, Integer latencyMs) {
        // 检查会话是否存在，不存在则创建
        RagSessionMeta meta = sessionMetaMapper.selectOne(
                new LambdaQueryWrapper<RagSessionMeta>()
                        .eq(RagSessionMeta::getSessionId, sessionId)
        );

        if (meta == null) {
            log.warn("会话不存在，自动创建 | sessionId={}", sessionId);
            createSession(tenantId, userId, query);
        }

        // 保存对话记录
        RagSession session = new RagSession();
        session.setSessionId(sessionId);
        session.setTenantId(tenantId);
        session.setUserId(userId);
        session.setQuery(query);
        session.setAnswer(answer);
        session.setContext(context);
        session.setTokensInput(tokensInput != null ? tokensInput : 0);
        session.setTokensOutput(tokensOutput != null ? tokensOutput : 0);
        session.setLatencyMs(latencyMs != null ? latencyMs : 0);

        sessionMapper.insert(session);

        // 异步更新会话元数据
        updateSessionMetaAsync(sessionId, query);
    }

    /**
     * 异步更新会话元数据（批量）
     */
    private void updateSessionMetaAsync(String sessionId, String lastQuery) {
        SessionCounter counter = sessionCounters.computeIfAbsent(sessionId, k -> new SessionCounter());
        counter.increment(lastQuery);

        // 每 5 次对话或超过 5 分钟更新一次数据库
        if (counter.count >= 5 ||
                Duration.between(counter.lastUpdateTime, LocalDateTime.now()).toMinutes() >= 5) {
            updateSessionMeta(sessionId, counter.lastQuery, counter.count);
            sessionCounters.remove(sessionId);
        }
    }

    @Override
    public void updateSessionMeta(String sessionId, String lastQuery, int messageCount) {
        RagSessionMeta meta = sessionMetaMapper.selectOne(
                new LambdaQueryWrapper<RagSessionMeta>()
                        .eq(RagSessionMeta::getSessionId, sessionId)
        );

        if (meta != null) {
            meta.setLastQuery(lastQuery);
            meta.setMessageCount(meta.getMessageCount() + messageCount);
            meta.setUpdateTime(LocalDateTime.now());
            sessionMetaMapper.updateById(meta);
            log.debug("更新会话元数据 | sessionId={} messageCount={}", sessionId, meta.getMessageCount());
        }
    }

    @Override
    public Page<RagSessionMeta> getSessionList(Long tenantId, Long userId,
                                                String keyword, List<String> tags,
                                                int page, int size) {
        int offset = (page - 1) * size;
        List<RagSessionMeta> records = sessionMetaMapper.selectSessionList(
                tenantId, userId, keyword, tags, offset, size);
        Long total = sessionMetaMapper.countSessionList(tenantId, userId, keyword, tags);

        Page<RagSessionMeta> result = new Page<>(page, size, total);
        result.setRecords(records);
        return result;
    }

    @Override
    public List<RagSession> getSessionDetail(Long tenantId, String sessionId) {
        return sessionMapper.selectList(
                new LambdaQueryWrapper<RagSession>()
                        .eq(RagSession::getTenantId, tenantId)
                        .eq(RagSession::getSessionId, sessionId)
                        .orderByAsc(RagSession::getCreateTime)
        );
    }

    @Override
    public void deleteSession(Long tenantId, String sessionId) {
        RagSessionMeta meta = sessionMetaMapper.selectOne(
                new LambdaQueryWrapper<RagSessionMeta>()
                        .eq(RagSessionMeta::getTenantId, tenantId)
                        .eq(RagSessionMeta::getSessionId, sessionId)
        );

        if (meta != null) {
            meta.setIsDeleted(true);
            meta.setUpdateTime(LocalDateTime.now());
            sessionMetaMapper.updateById(meta);
            log.info("软删除会话 | tenantId={} sessionId={}", tenantId, sessionId);
        }
    }

    @Override
    public void updateSession(Long tenantId, String sessionId, String title, List<String> tags) {
        RagSessionMeta meta = sessionMetaMapper.selectOne(
                new LambdaQueryWrapper<RagSessionMeta>()
                        .eq(RagSessionMeta::getTenantId, tenantId)
                        .eq(RagSessionMeta::getSessionId, sessionId)
        );

        if (meta != null) {
            if (title != null) {
                meta.setTitle(title);
            }
            if (tags != null) {
                meta.setTags(tags.toString());
            }
            meta.setUpdateTime(LocalDateTime.now());
            sessionMetaMapper.updateById(meta);
            log.info("更新会话信息 | tenantId={} sessionId={}", tenantId, sessionId);
        }
    }
}