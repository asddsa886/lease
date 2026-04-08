package com.atguigu.lease.web.app.chat.controller;

import com.atguigu.lease.common.result.Result;
import com.atguigu.lease.web.app.chat.dto.AssistantChatRequestVo;
import com.atguigu.lease.web.app.chat.dto.AssistantChatResponseVo;
import com.atguigu.lease.web.app.chat.service.AssistantChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/app/assistant")
@Tag(name = "智能助手")
public class AssistantChatController {

    private final AssistantChatService assistantChatService;

    @Operation(
            summary = "租房助手对话",
            description = """
                    用于和租房智能助手对话。
                    当前支持：
                    1. 查询房源列表
                    2. 查询房间详情
                    3. 查询我的预约
                    4. 查询我的租约
                    5. 简单闲聊和常识问答

                    可直接尝试：
                    - 帮我查一下朝阳区 3000 以内的房源
                    - 帮我看看我有哪些预约
                    - 帮我看看我有哪些租约
                    - 你可以帮我做什么？
                    """
    )
    @RequestBody(
            required = true,
            content = @Content(
                    schema = @Schema(implementation = AssistantChatRequestVo.class),
                    examples = {
                            @ExampleObject(name = "能力说明", value = "{\"message\":\"你可以帮我做什么？\"}"),
                            @ExampleObject(name = "房源查询", value = "{\"message\":\"帮我查一下朝阳区 3000 以内的房源\"}"),
                            @ExampleObject(name = "我的预约", value = "{\"message\":\"帮我看看我有哪些预约\"}"),
                            @ExampleObject(name = "我的租约", value = "{\"message\":\"帮我看看我有哪些租约\"}")
                    }
            )
    )
    @PostMapping("/chat")
    public Result<AssistantChatResponseVo> chat(@org.springframework.web.bind.annotation.RequestBody @Valid AssistantChatRequestVo requestVo) {
        return Result.ok(assistantChatService.chat(requestVo.getMessage()));
    }

    @Operation(
            summary = "租房助手流式对话",
            description = """
                    使用 SSE 流式返回助手输出。
                    事件类型包括：
                    - start：开始生成
                    - delta：增量文本
                    - tool_call：即将调用工具
                    - tool_result：工具执行完成
                    - complete：完整回复
                    - error：发生异常
                    """
    )
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@org.springframework.web.bind.annotation.RequestBody @Valid AssistantChatRequestVo requestVo) {
        return assistantChatService.stream(requestVo.getMessage());
    }
}
