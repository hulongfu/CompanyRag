package com.company.rag.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.entity.RagSessionMeta;

import java.util.List;

/**
 * RAG 会话服务接口
 */
public interface RagSessionService {

    /**
     * 创建新会话
     */
    RagSessionMeta createSession(Long tenantId, Long userId, String title);

    /**
     * 保存对话记录
     */
    void saveConversation(Long tenantId, String sessionId, Long userId,
                          String query, String answer, String context,
                          Integer tokensInput, Integer tokensOutput, Integer latencyMs);

    /**
     * 获取会话列表（分页 + 搜索）
     */
    Page<RagSessionMeta> getSessionList(Long tenantId, Long userId,
                                        String keyword, List<String> tags,
                                        int page, int size);

    /**
     * 获取会话详情
     */
    List<RagSession> getSessionDetail(Long tenantId, String sessionId);

    /**
     * 软删除会话
     */
    void deleteSession(Long tenantId, String sessionId);

    /**
     * 更新会话信息
     */
    void updateSession(Long tenantId, String sessionId, String title, List<String> tags);

    /**
     * 更新会话元数据（异步批量更新）
     */
    void updateSessionMeta(String sessionId, String lastQuery, int messageCount);
}