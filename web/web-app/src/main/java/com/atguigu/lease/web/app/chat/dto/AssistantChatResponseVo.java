package com.atguigu.lease.web.app.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "智能助手响应体")
public class AssistantChatResponseVo {

    @Schema(description = "当前会话ID，前端应复用该值继续多轮对话")
    private String conversationId;

    @Schema(description = "助手自然语言回复")
    private String reply;

    @Schema(description = "按段落拆分后的回复，便于前端渲染")
    private List<String> paragraphs;

    @Schema(description = "本轮答案来源，可能值：model / tool / rag / tool+rag")
    private String answerSource;

    @Schema(description = "模型结束原因")
    private String finishReason;

    @Schema(description = "本轮执行过的工具列表")
    private List<AssistantToolExecutionVo> toolExecutions;

    @Schema(description = "本轮命中的知识片段预览")
    private List<AssistantKnowledgeSourceVo> knowledgeSources;
}
