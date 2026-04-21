package com.atguigu.lease.web.app.assistant.service.memory;

public interface AssistantLongTermMemoryService {

    void rememberUserMessage(Long userId, String userMessage);

    String buildMemoryPrompt(Long userId);
}
