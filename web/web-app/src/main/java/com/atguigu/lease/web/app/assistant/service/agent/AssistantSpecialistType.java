package com.atguigu.lease.web.app.assistant.service.agent;

public enum AssistantSpecialistType {

    HOUSING_ADVISOR("advisor"),
    ORDER_SERVICE("order-service"),
    CUSTOMER_SUPPORT("customer-support");

    private final String taskType;

    AssistantSpecialistType(String taskType) {
        this.taskType = taskType;
    }

    public String getTaskType() {
        return taskType;
    }
}
