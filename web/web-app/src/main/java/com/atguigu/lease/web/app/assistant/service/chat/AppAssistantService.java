package com.atguigu.lease.web.app.assistant.service.chat;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatRequest;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AppAssistantService {

    AssistantChatResponse chat(AssistantChatRequest request, LoginUser currentUser);

    SseEmitter streamChat(AssistantChatRequest request, LoginUser currentUser);
}
