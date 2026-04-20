package com.atguigu.lease.web.app.assistant.service.agent;

public record AssistantSupervisorDecision(AssistantAgentRoute route,
                                          String reason,
                                          String goal) {

    public static AssistantSupervisorDecision fallback(AssistantAgentRoute route, String reason) {
        return new AssistantSupervisorDecision(route, reason, "");
    }
}
