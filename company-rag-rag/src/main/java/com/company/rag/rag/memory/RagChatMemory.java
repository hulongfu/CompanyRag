package com.company.rag.rag.memory;

import com.company.rag.rag.entity.RagSession;
import com.company.rag.rag.service.RagSessionService;
import com.company.rag.tenant.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读会话历史 helper。
 *
 * <p>职责：按 {@link TenantContext} 中的可信身份（tenantId / userId）读取有界历史，
 * 并转换为 {@link Message} 列表。只读不写库；窗口由 {@code rag.memory.window-size} 控制（单位=轮，一轮=2 行）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagChatMemory {

    private final RagSessionService ragSessionService;

    @Value("${rag.memory.window-size:50}")
    private int windowSize;

    /**
     * 获取有界历史消息（内部完成 RagSession→Message 转换，保持升序）。
     * 身份恒取 TenantContext（不可客户端伪造）；条件不足返回空列表（不 NPE）。
     */
    public List<Message> get(String sessionId) {
        Long tenantId = TenantContext.getTenantId();
        Long userId = TenantContext.getUserId();
        if (tenantId == null || userId == null || sessionId == null) {
            log.debug("会话历史读取条件不足，返回空列表：sessionId={}", sessionId);
            return List.of();
        }
        // 一轮=2 行（Q+A）；window-size<=0 视为 -1 退化全量（service 层处理 limit<=0）
        int limit = (windowSize > 0) ? windowSize * 2 : 0;
        List<RagSession> sessions =
                ragSessionService.getRecentSessionDetail(tenantId, userId, sessionId, limit);
        return toMessages(sessions);
    }

    /**
     * RagSession→Message：Q→UserMessage，A→AssistantMessage，保持传入顺序（升序）。
     */
    private List<Message> toMessages(List<RagSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(sessions.size() * 2);
        for (RagSession s : sessions) {
            messages.add(new UserMessage(s.getQuery()));
            messages.add(new AssistantMessage(s.getAnswer()));
        }
        return messages;
    }
}