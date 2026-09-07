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
    List<RagSession> getSessionDetail(Long tenantId, Long userId, String sessionId);

    /**
     * 软删除会话
     */
    void deleteSession(Long tenantId, Long userId, String sessionId);

    /**
     * 更新会话信息
     */
    void updateSession(Long tenantId, Long userId, String sessionId, String title, List<String> tags);

    /**
     * 更新会话元数据（异步批量更新）
     */
    void updateSessionMeta(String sessionId, String lastQuery, int messageCount);

    /**
     * 更新用户反馈（👍/👎）
     * @param tenantId 租户 ID
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param feedback 反馈值：-1=👎, 0=清除，1=👍
     */
    void updateFeedback(Long tenantId, Long userId, String sessionId, Short feedback);
}