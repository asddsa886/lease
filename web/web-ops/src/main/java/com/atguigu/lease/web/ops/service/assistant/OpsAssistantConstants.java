package com.atguigu.lease.web.ops.service.assistant;

import java.util.Set;

public final class OpsAssistantConstants {

    public static final String TASK_TYPE = "ops-assistant";
    public static final String ROUTER_AGENT_NAME = "ops-supervisor-main";
    public static final String APP_AGENT_NAME = "ops-app-agent";
    public static final String INFRA_AGENT_NAME = "ops-infra-agent";
    public static final String PERFORMANCE_AGENT_NAME = "ops-performance-agent";
    public static final String SPECIALIST_REPLY_KEY = "ops_assistant_reply";
    private static final Set<String> ROUTING_TOKENS = Set.of(
            APP_AGENT_NAME,
            INFRA_AGENT_NAME,
            PERFORMANCE_AGENT_NAME,
            "FINISH"
    );

    private OpsAssistantConstants() {
    }

    public static boolean isRoutingToken(String value) {
        return value != null && ROUTING_TOKENS.contains(value.trim());
    }
}
