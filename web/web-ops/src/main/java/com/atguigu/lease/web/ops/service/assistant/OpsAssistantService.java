package com.atguigu.lease.web.ops.service.assistant;

import com.atguigu.lease.web.ops.dto.OpsAssistantChatRequest;
import com.atguigu.lease.web.ops.dto.OpsAssistantChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface OpsAssistantService {

    OpsAssistantChatResponse chat(OpsAssistantChatRequest request);

    SseEmitter streamChat(OpsAssistantChatRequest request);
}
