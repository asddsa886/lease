package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;

import java.util.List;

public record SupervisorExecutionResult(
        SupervisorPlan plan,
        List<SupervisorPlanStep> executedSteps,
        SpecialistAgentType primaryAgentType,
        String reply,
        List<AssistantNextAction> nextActions,
        SupervisorClarification clarification
) {

    public boolean needsClarification() {
        return clarification != null;
    }
}
