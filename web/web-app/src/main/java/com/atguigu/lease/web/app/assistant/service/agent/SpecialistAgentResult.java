package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;

import java.util.List;

public record SpecialistAgentResult(
        SpecialistAgentType type,
        String reply,
        List<AssistantNextAction> nextActions
) {
}
