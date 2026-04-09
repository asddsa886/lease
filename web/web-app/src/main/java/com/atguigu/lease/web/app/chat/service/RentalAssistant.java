package com.atguigu.lease.web.app.chat.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface RentalAssistant {

    @SystemMessage(AssistantPrompts.SYSTEM_MESSAGE)
    Result<String> chat(@MemoryId String conversationId, @UserMessage String message);
}
