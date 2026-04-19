package com.atguigu.lease.web.app.assistant.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantStreamPayload {

    private String conversationId;

    private String message;

    private String content;

    private String toolName;

    private String reply;

    private List<String> paragraphs;

    private AssistantTaskState taskState;

    private List<AssistantNextAction> nextActions;
}
