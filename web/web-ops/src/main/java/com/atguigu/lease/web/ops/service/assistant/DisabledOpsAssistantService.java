package com.atguigu.lease.web.ops.service.assistant;

import com.atguigu.lease.web.ops.dto.OpsAssistantChatRequest;
import com.atguigu.lease.web.ops.dto.OpsAssistantChatResponse;
import com.atguigu.lease.web.ops.dto.OpsAssistantStreamPayload;
import com.atguigu.lease.web.ops.dto.OpsAssistantTaskState;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;

public class DisabledOpsAssistantService implements OpsAssistantService {

    @Override
    public OpsAssistantChatResponse chat(OpsAssistantChatRequest request) {
        return OpsAssistantChatResponse.builder()
                .conversationId(resolveConversationId(request))
                .reply("运维助手当前未启用，请先配置 Spring AI ChatModel。")
                .taskState(OpsAssistantTaskState.builder().type("ops-assistant").status("failed").build())
                .build();
    }

    @Override
    public SseEmitter streamChat(OpsAssistantChatRequest request) {
        SseEmitter emitter = new SseEmitter(10_000L);
        try {
            emitter.send(SseEmitter.event().name("error").data(OpsAssistantStreamPayload.builder()
                    .conversationId(resolveConversationId(request))
                    .message("运维助手当前未启用，请先配置 Spring AI ChatModel。")
                    .taskState(OpsAssistantTaskState.builder().type("ops-assistant").status("failed").build())
                    .build()));
        } catch (IOException ignored) {
        }
        emitter.complete();
        return emitter;
    }

    private String resolveConversationId(OpsAssistantChatRequest request) {
        if (request != null && StringUtils.hasText(request.getConversationId())) {
            return request.getConversationId().trim();
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
