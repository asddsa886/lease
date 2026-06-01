package com.atguigu.lease.web.app.assistant.service.agent;

public record SupervisorClarification(
        SpecialistAgentType primaryAgentType,
        String question
) {
}
