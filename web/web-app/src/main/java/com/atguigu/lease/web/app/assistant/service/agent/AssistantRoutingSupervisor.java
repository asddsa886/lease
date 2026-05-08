package com.atguigu.lease.web.app.assistant.service.agent;

import java.util.Locale;

public class AssistantRoutingSupervisor {

    public AssistantRouteDecision route(String userMessage) {
        String normalized = normalize(userMessage);

        boolean supportIntent = containsAny(normalized,
                "为什么", "怎么", "流程", "规则", "什么意思", "超时", "失败", "下一步", "如何", "faq", "客服", "说明");
        boolean orderExplainIntent = containsAny(normalized,
                "订单", "签约", "支付", "租约", "取消订单", "下单", "续约", "订单详情", "订单状态");
        boolean orderActionIntent = containsAny(normalized,
                "查订单", "我的订单", "订单被取消", "支付订单", "取消订单", "签约订单", "租约状态");
        boolean advisorIntent = containsAny(normalized,
                "推荐", "适合", "预算", "找房", "房源", "公寓", "房间", "浏览", "最近看过", "看过哪些",
                "租房建议", "帮我找", "帮我推荐", "选房", "看房", "预约");

        if (advisorIntent) {
            return new AssistantRouteDecision(
                    AssistantSpecialistType.HOUSING_ADVISOR,
                    null,
                    "advisor-intent"
            );
        }

        if (orderActionIntent && supportIntent) {
            return new AssistantRouteDecision(
                    AssistantSpecialistType.ORDER_SERVICE,
                    AssistantSpecialistType.CUSTOMER_SUPPORT,
                    "order-with-explanation"
            );
        }

        if (supportIntent && !orderActionIntent) {
            return new AssistantRouteDecision(
                    AssistantSpecialistType.CUSTOMER_SUPPORT,
                    orderExplainIntent ? AssistantSpecialistType.ORDER_SERVICE : null,
                    orderExplainIntent ? "support-with-order-context" : "support-intent"
            );
        }

        if (orderActionIntent || orderExplainIntent) {
            return new AssistantRouteDecision(
                    AssistantSpecialistType.ORDER_SERVICE,
                    null,
                    "order-intent"
            );
        }

        return new AssistantRouteDecision(
                AssistantSpecialistType.CUSTOMER_SUPPORT,
                null,
                "default-support"
        );
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim()
                .replace(" ", "")
                .replace("\u3000", "")
                .toLowerCase(Locale.ROOT);
    }
}
