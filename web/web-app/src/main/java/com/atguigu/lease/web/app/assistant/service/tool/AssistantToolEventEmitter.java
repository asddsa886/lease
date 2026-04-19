package com.atguigu.lease.web.app.assistant.service.tool;

@FunctionalInterface
public interface AssistantToolEventEmitter {

    void emit(String eventName, String toolName, String message);

    static AssistantToolEventEmitter noop() {
        return (eventName, toolName, message) -> {
        };
    }
}
