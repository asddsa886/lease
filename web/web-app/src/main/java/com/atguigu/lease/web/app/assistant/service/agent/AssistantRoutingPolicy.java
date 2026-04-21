package com.atguigu.lease.web.app.assistant.service.agent;

import java.util.List;
import java.util.Locale;

public class AssistantRoutingPolicy {

    private static final List<String> BUSINESS_KEYWORDS = List.of(
            "预约", "看房", "改约", "改时间", "取消预约",
            "订单", "签约", "租约", "下单", "创建订单", "取消订单",
            "支付", "提交", "确认", "办理"
    );

    public AssistantAgentRoute classify(String userMessage) {
        String normalized = normalize(userMessage);
        if (containsAny(normalized, BUSINESS_KEYWORDS)) {
            return AssistantAgentRoute.BUSINESS_EXECUTION;
        }
        return AssistantAgentRoute.SEARCH_QA;
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace(" ", "");
    }
}
