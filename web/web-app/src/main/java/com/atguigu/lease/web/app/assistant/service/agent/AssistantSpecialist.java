package com.atguigu.lease.web.app.assistant.service.agent;

public interface AssistantSpecialist {

    AssistantSpecialistType type();

    AssistantSpecialistResult handle(AssistantSpecialistRequest request);
}
