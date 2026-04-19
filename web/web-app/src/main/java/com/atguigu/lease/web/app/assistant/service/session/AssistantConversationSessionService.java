package com.atguigu.lease.web.app.assistant.service.session;

import java.util.List;

public interface AssistantConversationSessionService {

    String resolveConversationId(Long userId, String requestedConversationId);

    List<AssistantConversationMessage> getMessages(Long userId, String conversationId);

    void appendConversation(Long userId, String conversationId, String userMessage, String assistantMessage);
}
