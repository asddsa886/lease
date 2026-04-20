package com.atguigu.lease.web.app.assistant.service.agent;

import java.util.Arrays;

public enum AssistantAgentRoute {

    GENERAL("general", "general", "通用助理"),
    ROOM_SEARCH("room_search", "room_search", "找房专员"),
    APPOINTMENT("appointment", "appointment", "预约专员"),
    LEASE_ORDER("lease_order", "lease_order", "订单专员"),
    RENTAL_WORKFLOW("rental_workflow", "workflow", "租房任务编排专员");

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
