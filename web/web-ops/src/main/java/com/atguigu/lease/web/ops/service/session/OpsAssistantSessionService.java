package com.atguigu.lease.web.ops.service.session;

import com.atguigu.lease.web.ops.config.OpsAssistantProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OpsAssistantSessionService {

    private static final int MAX_TURNS = 6;

    private final OpsAssistantProperties assistantProperties;
    private final ConcurrentHashMap<String, OpsAssistantSessionState> sessions = new ConcurrentHashMap<>();

    public OpsAssistantSessionService(OpsAssistantProperties assistantProperties) {
        this.assistantProperties = assistantProperties;
    }

    public OpsAssistantSessionState getOrCreate(String conversationId) {
        clearExpiredSessions();
        Date now = new Date();
        return sessions.compute(conversationId, (key, existing) -> {
            if (existing == null) {
                return OpsAssistantSessionState.builder()
                        .conversationId(conversationId)
                        .createdAt(now)
                        .lastAccessedAt(now)
                        .recentTurns(new ArrayList<>())
                        .build();
            }
            existing.setLastAccessedAt(now);
            return existing;
        });
    }

    public void remember(String conversationId,
                         String userMessage,
                         String assistantReply,
                         Long scanId,
                         String focusCategory) {
        OpsAssistantSessionState state = getOrCreate(conversationId);
        Date now = new Date();
        if (StringUtils.hasText(userMessage)) {
            state.getRecentTurns().add(OpsAssistantSessionState.ConversationTurn.builder()
                    .role("user")
                    .content(userMessage)
                    .time(now)
                    .build());
        }
        if (StringUtils.hasText(assistantReply)) {
            state.getRecentTurns().add(OpsAssistantSessionState.ConversationTurn.builder()
                    .role("assistant")
                    .content(assistantReply)
                    .time(now)
                    .build());
        }
        trimTurns(state.getRecentTurns());
        if (scanId != null) {
            state.setCurrentScanId(scanId);
        }
        if (StringUtils.hasText(focusCategory)) {
            state.setFocusCategory(focusCategory);
        }
        state.setLastAccessedAt(now);
    }

    public String buildContextPrompt(String conversationId) {
        OpsAssistantSessionState state = getOrCreate(conversationId);
        StringBuilder builder = new StringBuilder();
        builder.append("当前会话上下文：").append(System.lineSeparator());
        if (state.getCurrentScanId() != null) {
            builder.append("- 当前关注扫描ID：").append(state.getCurrentScanId()).append(System.lineSeparator());
        }
        if (StringUtils.hasText(state.getFocusCategory())) {
            builder.append("- 当前关注问题分类：").append(state.getFocusCategory()).append(System.lineSeparator());
        }
        if (state.getRecentTurns() != null && !state.getRecentTurns().isEmpty()) {
            builder.append("- 最近对话：").append(System.lineSeparator());
            for (OpsAssistantSessionState.ConversationTurn turn : state.getRecentTurns()) {
                builder.append("  - ")
                        .append("user".equals(turn.getRole()) ? "用户" : "助手")
                        .append("：")
                        .append(turn.getContent())
                        .append(System.lineSeparator());
            }
        }
        return builder.toString().trim();
    }

    private void trimTurns(List<OpsAssistantSessionState.ConversationTurn> turns) {
        while (turns.size() > MAX_TURNS) {
            turns.remove(0);
        }
    }

    private void clearExpiredSessions() {
        long ttlMillis = assistantProperties.getSessionTtl().toMillis();
        Date now = new Date();
        sessions.entrySet().removeIf(entry -> {
            Date lastAccessedAt = entry.getValue().getLastAccessedAt();
            if (lastAccessedAt == null) {
                return true;
            }
            return now.getTime() - lastAccessedAt.getTime() > ttlMillis;
        });
    }
}
