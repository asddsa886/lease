package com.atguigu.lease.web.app.chat.service;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface AppointmentActionAnalyzer {

    @SystemMessage(AssistantPrompts.APPOINTMENT_ACTION_ANALYZER_SYSTEM_MESSAGE)
    String analyze(@UserMessage String message);
}
