package com.atguigu.lease.web.app.assistant.service.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantToolResult {

    private boolean success;

    private String message;

    private Object data;

    public static AssistantToolResult ok(String message, Object data) {
        return AssistantToolResult.builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static AssistantToolResult fail(String message) {
        return AssistantToolResult.builder()
                .success(false)
                .message(message)
                .build();
    }
}
