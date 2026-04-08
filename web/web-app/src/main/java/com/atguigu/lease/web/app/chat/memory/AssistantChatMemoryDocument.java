package com.atguigu.lease.web.app.chat.memory;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Document("assistant_chat_memory")
public class AssistantChatMemoryDocument {

    @Id
    private String id;

    private String memoryId;

    /**
     * New structured storage format.
     */
    private List<Map<String, Object>> messages;

    /**
     * Backward-compatible fallback for previously persisted string content.
     */
    private String content;

    private LocalDateTime updateTime;
}
