package com.atguigu.lease.web.ops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "运维助手对话请求")
public class OpsAssistantChatRequest {

    @Schema(description = "会话ID，续聊时传入，首次对话可不传")
    private String conversationId;

    @NotBlank(message = "消息不能为空")
    @Schema(description = "用户消息")
    private String message;
}
