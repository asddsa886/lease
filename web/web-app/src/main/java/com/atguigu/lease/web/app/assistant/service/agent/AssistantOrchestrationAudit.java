package com.atguigu.lease.web.app.assistant.service.agent;

import java.util.List;
import java.util.Set;

public record AssistantOrchestrationAudit(
        String conversationId,
        SupervisorPlan plan,
        List<SpecialistAgentType> executedAgents,
        Set<String> toolsUsed,
        boolean ragUsed,
        boolean clarificationUsed,
        boolean fallbackUsed,
        String fallbackReason
) {
}
