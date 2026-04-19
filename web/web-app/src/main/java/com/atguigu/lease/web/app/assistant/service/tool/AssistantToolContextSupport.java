package com.atguigu.lease.web.app.assistant.service.tool;

import org.springframework.ai.chat.model.ToolContext;

public final class AssistantToolContextSupport {

    public static final String CURRENT_USER_ID = "currentUserId";
    public static final String CONVERSATION_ID = "conversationId";
    public static final String TOOL_EVENT_EMITTER = "toolEventEmitter";

    private AssistantToolContextSupport() {
    }

    public static Long currentUserId(ToolContext toolContext) {
        Object value = toolContext.getContext().get(CURRENT_USER_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        throw new IllegalStateException("Missing currentUserId in tool context");
    }

    public static AssistantToolEventEmitter eventEmitter(ToolContext toolContext) {
        Object value = toolContext.getContext().get(TOOL_EVENT_EMITTER);
        if (value instanceof AssistantToolEventEmitter emitter) {
            return emitter;
        }
        return AssistantToolEventEmitter.noop();
    }
}
