package com.atguigu.lease.web.app.assistant.controller;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.common.login.LoginUserHolder;
import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.common.result.ResultCodeEnum;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatRequest;
import com.atguigu.lease.web.app.assistant.dto.AssistantChatResponse;
import com.atguigu.lease.web.app.assistant.service.chat.AppAssistantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping("/app/assistant")
@Tag(name = "AI助手")
public class AssistantController {

    private final AppAssistantService assistantService;

    public AssistantController(AppAssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @Operation(summary = "普通对话")
    @PostMapping("/chat")
    public Result<AssistantChatResponse> chat(@RequestBody @Valid AssistantChatRequest request) {
        return Result.ok(assistantService.chat(request, requireLoginUser()));
    }

    @Operation(summary = "流式对话")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody @Valid AssistantChatRequest request) {
        return assistantService.streamChat(request, requireLoginUser());
    }

    private LoginUser requireLoginUser() {
        LoginUser loginUser = LoginUserHolder.get();
        if (loginUser == null) {
            throw new LeaseException(ResultCodeEnum.APP_LOGIN_AUTH);
        }
        return loginUser;
    }
}
