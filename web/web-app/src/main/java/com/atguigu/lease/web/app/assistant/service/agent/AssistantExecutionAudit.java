package com.atguigu.lease.web.app.assistant.service.agent;

import java.util.Set;

public record AssistantExecutionAudit(
        String conversationId,
        AssistantSpecialistType primary,
        AssistantSpecialistType secondary,
        Set<String> toolsUsed,
        boolean ragUsed,
        boolean fallbackUsed,
        String fallbackReason
) {
}
