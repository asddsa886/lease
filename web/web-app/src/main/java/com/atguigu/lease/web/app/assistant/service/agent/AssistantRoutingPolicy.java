package com.atguigu.lease.web.app.assistant.service.agent;

import java.util.List;
import java.util.Locale;

public class AssistantRoutingPolicy {

    private static final List<String> ROOM_KEYWORDS = List.of(
            "房源", "找房", "租房", "公寓", "房子", "房间", "租金", "筛选", "推荐", "第一个房源", "第二个房源"
    );
    private static final List<String> APPOINTMENT_KEYWORDS = List.of(
            "预约", "看房", "改约", "改时间", "取消预约", "约一个", "约个时间"
    );
    private static final List<String> ORDER_KEYWORDS = List.of(
            "订单", "签约", "租约", "下单", "签合同", "创建订单", "取消订单"
    );

    public AssistantAgentRoute classify(String userMessage) {
        String normalized = normalize(userMessage);
        boolean roomIntent = containsAny(normalized, ROOM_KEYWORDS);
        boolean appointmentIntent = containsAny(normalized, APPOINTMENT_KEYWORDS);
        boolean orderIntent = containsAny(normalized, ORDER_KEYWORDS);

        if ((roomIntent && appointmentIntent) || (roomIntent && orderIntent) || (appointmentIntent && orderIntent)) {
            return AssistantAgentRoute.RENTAL_WORKFLOW;
        }
        if (roomIntent && containsAny(normalized, List.of("顺便", "然后", "再帮我", "同时"))) {
            return AssistantAgentRoute.RENTAL_WORKFLOW;
        }
        if (appointmentIntent) {
            return AssistantAgentRoute.APPOINTMENT;
        }
        if (orderIntent) {
            return AssistantAgentRoute.LEASE_ORDER;
        }
        if (roomIntent) {
            return AssistantAgentRoute.ROOM_SEARCH;
        }
        return AssistantAgentRoute.GENERAL;
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace(" ", "");
    }
}
