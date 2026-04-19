package com.atguigu.lease.web.app.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "AI助手对话请求")
public class AssistantChatRequest {

    @NotBlank(message = "message不能为空")
    @Schema(description = "用户输入")
    private String message;

    @Schema(description = "会话ID，为空时自动生成")
    private String conversationId;
}
