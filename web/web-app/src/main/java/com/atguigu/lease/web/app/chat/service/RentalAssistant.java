package com.atguigu.lease.web.app.chat.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface RentalAssistant {

    @SystemMessage(AssistantPrompts.SYSTEM_MESSAGE)
    String chat(@UserMessage String message);
}
