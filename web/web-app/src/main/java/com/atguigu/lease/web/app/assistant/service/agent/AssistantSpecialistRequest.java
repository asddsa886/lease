package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationMessage;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolEventEmitter;

import java.util.List;

public record AssistantSpecialistRequest(
        LoginUser currentUser,
        String conversationId,
        String userMessage,
        List<AssistantConversationMessage> history,
        String extraInstructions,
        AssistantToolEventEmitter toolEventEmitter
) {
}
