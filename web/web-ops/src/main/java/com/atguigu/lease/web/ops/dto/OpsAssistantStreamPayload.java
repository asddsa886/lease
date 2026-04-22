package com.atguigu.lease.web.ops.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpsAssistantStreamPayload {

    private String conversationId;

    private String message;

    private String content;

    private String toolName;

    private String reply;

    private OpsAssistantTaskState taskState;
}
