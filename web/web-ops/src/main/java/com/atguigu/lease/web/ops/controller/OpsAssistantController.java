package com.atguigu.lease.web.ops.controller;

import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.web.ops.dto.OpsAssistantChatRequest;
import com.atguigu.lease.web.ops.dto.OpsAssistantChatResponse;
import com.atguigu.lease.web.ops.service.assistant.OpsAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "运维聊天助手")
@RestController
@RequestMapping("/ops/assistant")
public class OpsAssistantController {

    private final OpsAssistantService assistantService;

    public OpsAssistantController(OpsAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @Operation(summary = "运维助手普通对话")
    @PostMapping("chat")
    public Result<OpsAssistantChatResponse> chat(@Valid @RequestBody OpsAssistantChatRequest request) {
        return Result.ok(assistantService.chat(request));
    }

    @Operation(summary = "运维助手流式对话")
    @PostMapping(value = "chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody OpsAssistantChatRequest request) {
        return assistantService.streamChat(request);
    }
}
