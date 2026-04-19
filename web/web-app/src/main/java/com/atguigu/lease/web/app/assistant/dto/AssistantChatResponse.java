package com.atguigu.lease.web.app.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AI助手对话响应")
public class AssistantChatResponse {

    @Schema(description = "会话ID")
    private String conversationId;

    @Schema(description = "助手回复")
    private String reply;

    @Schema(description = "段落化回复")
    private List<String> paragraphs;

    @Schema(description = "当前任务状态")
    private AssistantTaskState taskState;

    @Schema(description = "建议下一步操作")
    private List<AssistantNextAction> nextActions;
}
