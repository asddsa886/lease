package com.atguigu.lease.web.app.chat.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface StreamingToolFirstRentalAssistant {

    @SystemMessage(AssistantPrompts.SYSTEM_MESSAGE)
    TokenStream chat(@MemoryId String conversationId, @UserMessage String message);
}
