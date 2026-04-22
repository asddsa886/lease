package com.atguigu.lease.web.ops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "运维助手对话响应")
public class OpsAssistantChatResponse {

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "助手回复")
    private String reply;

    @Schema(description = "任务状态")
    private OpsAssistantTaskState taskState;
}
