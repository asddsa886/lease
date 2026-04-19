package com.atguigu.lease.web.app.assistant.service.chat;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatRequest;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatResponse;
import com.atguigu.lease.web.app.assistant.dto.AssistantStreamPayload;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

public class DisabledAssistantService implements AppAssistantService {

    private final AssistantPromptService promptService;

    public DisabledAssistantService(AssistantPromptService promptService) {
        this.promptService = promptService;
    }

    @Override
    public AssistantChatResponse chat(AssistantChatRequest request, LoginUser currentUser) {
        String conversationId = request.getConversationId() == null || request.getConversationId().isBlank()
                ? "assistant-disabled"
                : request.getConversationId().trim();
        return promptService.buildResponse(
                conversationId,
                "AI助手当前未启用。请配置 LEASE_ASSISTANT_ENABLED=true、SPRING_AI_CHAT_MODEL=openai，以及对应的 Spring AI OpenAI 模型参数后再试。"
        );
    }

    @Override
    public SseEmitter streamChat(AssistantChatRequest request, LoginUser currentUser) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(AssistantStreamPayload.builder()
                            .conversationId(request.getConversationId())
                            .message("AI助手当前未启用，请先完成 Spring AI 模型配置。")
                            .build()));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
