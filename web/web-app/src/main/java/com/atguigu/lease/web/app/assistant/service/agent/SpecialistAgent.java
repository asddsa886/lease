package com.atguigu.lease.web.app.assistant.service.agent;

public interface SpecialistAgent {

    SpecialistAgentType type();

    SpecialistAgentResult execute(SpecialistAgentRequest request);
}
