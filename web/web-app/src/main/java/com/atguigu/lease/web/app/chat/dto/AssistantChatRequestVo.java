package com.atguigu.lease.web.app.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "智能助手请求体")
public class AssistantChatRequestVo {

    @NotBlank(message = "message 不能为空")
    @Schema(description = "用户输入的问题", example = "帮我查一下朝阳区 3000 以内的房源")
    private String message;
}
