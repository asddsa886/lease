package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolEventEmitter;

import java.util.LinkedHashSet;
import java.util.Set;

public class AuditedToolEventEmitter implements AssistantToolEventEmitter {

    private final AssistantToolEventEmitter delegate;
    private final Set<String> toolNames = new LinkedHashSet<>();

    public AuditedToolEventEmitter(AssistantToolEventEmitter delegate) {
        this.delegate = delegate;
    }

    @Override
    public void emit(String eventName, String toolName, String message) {
        if (toolName != null && !toolName.isBlank()) {
            toolNames.add(toolName);
        }
        delegate.emit(eventName, toolName, message);
    }

    public Set<String> toolNames() {
        return toolNames;
    }
}
