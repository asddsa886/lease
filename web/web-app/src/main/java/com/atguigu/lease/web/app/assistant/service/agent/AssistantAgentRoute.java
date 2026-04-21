package com.atguigu.lease.web.app.assistant.service.agent;

import java.util.Arrays;

public enum AssistantAgentRoute {

    SEARCH_QA("search_qa", "search_qa", "检索问答专员"),
    BUSINESS_EXECUTION("business_execution", "business_execution", "业务执行专员");

    private final String code;
    private final String taskType;
    private final String displayName;

    AssistantAgentRoute(String code, String taskType, String displayName) {
        this.code = code;
        this.taskType = taskType;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public String taskType() {
        return taskType;
    }

    public String displayName() {
        return displayName;
    }

    public static AssistantAgentRoute fromValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String normalized = rawValue.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(route -> route.code.equals(normalized)
                        || route.name().toLowerCase().equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
