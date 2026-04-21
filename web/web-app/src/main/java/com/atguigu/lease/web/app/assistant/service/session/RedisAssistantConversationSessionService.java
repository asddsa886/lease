package com.atguigu.lease.web.app.assistant.service.session;

import com.atguigu.lease.web.app.assistant.config.AssistantProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RedisAssistantConversationSessionService {

    private static final TypeReference<List<AssistantConversationMessage>> MESSAGE_LIST_TYPE =
            new TypeReference<>() {
            };

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final AssistantProperties assistantProperties;

    public RedisAssistantConversationSessionService(StringRedisTemplate stringRedisTemplate,
                                                   ObjectMapper objectMapper,
                                                   AssistantProperties assistantProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.assistantProperties = assistantProperties;
    }

    public String resolveConversationId(Long userId, String requestedConversationId) {
        if (requestedConversationId != null && !requestedConversationId.isBlank()) {
            return requestedConversationId.trim();
        }
        return "assistant-" + userId + "-" + UUID.randomUUID().toString().replace("-", "");
    }

    public List<AssistantConversationMessage> getMessages(Long userId, String conversationId) {
        String cache = stringRedisTemplate.opsForValue().get(buildKey(userId, conversationId));
        if (cache == null || cache.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(cache, MESSAGE_LIST_TYPE);
        } catch (IOException e) {
            stringRedisTemplate.delete(buildKey(userId, conversationId));
            return List.of();
        }
    }

    public void appendConversation(Long userId, String conversationId, String userMessage, String assistantMessage) {
        List<AssistantConversationMessage> messages = new ArrayList<>(getMessages(userId, conversationId));
        LocalDateTime now = LocalDateTime.now();
        messages.add(AssistantConversationMessage.builder()
                .role("user")
                .content(userMessage)
                .createdAt(now)
                .build());
        messages.add(AssistantConversationMessage.builder()
                .role("assistant")
                .content(assistantMessage)
                .createdAt(now)
                .build());

        int maxHistoryMessages = Math.max(assistantProperties.getMaxHistoryMessages(), 2);
        if (messages.size() > maxHistoryMessages) {
            messages = new ArrayList<>(messages.subList(messages.size() - maxHistoryMessages, messages.size()));
        }

        try {
            stringRedisTemplate.opsForValue().set(
                    buildKey(userId, conversationId),
                    objectMapper.writeValueAsString(messages),
                    assistantProperties.getConversationTtl().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist assistant conversation", e);
        }
    }

    private String buildKey(Long userId, String conversationId) {
        return "assistant:conversation:" + userId + ":" + conversationId;
    }
}
