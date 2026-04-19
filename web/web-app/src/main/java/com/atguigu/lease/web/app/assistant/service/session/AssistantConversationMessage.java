package com.atguigu.lease.web.app.assistant.service.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AssistantConversationMessage {

    private String role;

    private String content;

    private LocalDateTime createdAt;
}
