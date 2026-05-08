package com.atguigu.lease.web.app.assistant.service.agent;

public record AssistantRouteDecision(
        AssistantSpecialistType primary,
        AssistantSpecialistType secondary,
        String reason
) {

    public boolean hasSecondary() {
        return secondary != null;
    }
}
