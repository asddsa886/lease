package com.atguigu.lease.web.app.assistant.service.agent;

import java.util.Arrays;
import java.util.Locale;

public enum SpecialistAgentType {

    HOUSING_ADVISOR("housing-advisor", "advisor"),
    ORDER_SERVICE("order-service", "order-service"),
    CUSTOMER_SUPPORT("customer-support", "customer-support");

    private final String planName;
    private final String taskType;

    SpecialistAgentType(String planName, String taskType) {
        this.planName = planName;
        this.taskType = taskType;
    }

    public String getPlanName() {
        return planName;
    }

    public String getTaskType() {
        return taskType;
    }

    public static SpecialistAgentType fromPlanName(String planName) {
        if (planName == null || planName.isBlank()) {
            return null;
        }
        String normalized = planName.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(item -> item.planName.equals(normalized))
                .findFirst()
                .orElse(null);
    }
}
