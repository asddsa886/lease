package com.atguigu.lease.web.app.chat.memory;

import com.atguigu.lease.web.app.chat.config.AssistantProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RequiredArgsConstructor
public class AssistantMongoChatMemoryStore implements ChatMemoryStore {

    private static final TypeReference<List<Map<String, Object>>> MESSAGE_LIST_TYPE = new TypeReference<>() {
    };

    private final AssistantProperties assistantProperties;
    private final ObjectProvider<MongoTemplate> mongoTemplateProvider;
    private final ObjectMapper objectMapper;
    private final Map<String, String> fallbackStore = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = toKey(memoryId);
        String content = readFromMongo(key);
        if (!StringUtils.hasText(content)) {
            content = fallbackStore.get(key);
        }
        if (!StringUtils.hasText(content)) {
            return new ArrayList<>();
        }
        return ChatMessageDeserializer.messagesFromJson(content);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = toKey(memoryId);
        String content = ChatMessageSerializer.messagesToJson(messages);
        fallbackStore.put(key, content);

        if (!assistantProperties.isMongoMemoryEnabled()) {
            return;
        }

        try {
            MongoTemplate mongoTemplate = mongoTemplateProvider.getIfAvailable();
            if (mongoTemplate == null) {
                return;
            }
            List<Map<String, Object>> structuredMessages = objectMapper.readValue(content, MESSAGE_LIST_TYPE);
            Query query = new Query(Criteria.where("memoryId").is(key));
            Update update = new Update()
                    .set("memoryId", key)
                    .set("messages", structuredMessages)
                    .set("updateTime", LocalDateTime.now());
            update.unset("content");
            mongoTemplate.upsert(query, update, AssistantChatMemoryDocument.class);
        } catch (Exception e) {
            log.warn("Assistant chat memory fallback to local map, memoryId={}", key, e);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = toKey(memoryId);
        fallbackStore.remove(key);

        if (!assistantProperties.isMongoMemoryEnabled()) {
            return;
        }

        try {
            MongoTemplate mongoTemplate = mongoTemplateProvider.getIfAvailable();
            if (mongoTemplate == null) {
                return;
            }
            Query query = new Query(Criteria.where("memoryId").is(key));
            mongoTemplate.remove(query, AssistantChatMemoryDocument.class);
        } catch (Exception e) {
            log.warn("Assistant chat memory delete failed, memoryId={}", key, e);
        }
    }

    public void clearConversation(String conversationId) {
        deleteMessages(conversationId);
    }

    private String readFromMongo(String key) {
        if (!assistantProperties.isMongoMemoryEnabled()) {
            return null;
        }

        try {
            MongoTemplate mongoTemplate = mongoTemplateProvider.getIfAvailable();
            if (mongoTemplate == null) {
                return null;
            }
            Query query = new Query(Criteria.where("memoryId").is(key));
            AssistantChatMemoryDocument document = mongoTemplate.findOne(query, AssistantChatMemoryDocument.class);
            if (document == null) {
                return null;
            }
            if (document.getMessages() != null && !document.getMessages().isEmpty()) {
                return objectMapper.writeValueAsString(document.getMessages());
            }
            return document.getContent();
        } catch (Exception e) {
            log.warn("Assistant chat memory read failed, memoryId={}", key, e);
            return null;
        }
    }

    private String toKey(Object memoryId) {
        return memoryId == null ? "default" : String.valueOf(memoryId);
    }
}
