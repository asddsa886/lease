package com.atguigu.lease.web.app.assistant.service.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantRoutingSupervisorTest {

    private final AssistantRoutingSupervisor supervisor = new AssistantRoutingSupervisor();

    @Test
    void shouldRouteAdvisorIntentForRecommendationScenario() {
        AssistantRouteDecision decision = supervisor.route("我预算3000，在朝阳，推荐最适合签约的三个房间");

        assertThat(decision.primary()).isEqualTo(AssistantSpecialistType.HOUSING_ADVISOR);
        assertThat(decision.secondary()).isNull();
    }

    @Test
    void shouldRouteOrderIntentForOrderQuestion() {
        AssistantRouteDecision decision = supervisor.route("帮我查我的签约订单状态");

        assertThat(decision.primary()).isEqualTo(AssistantSpecialistType.ORDER_SERVICE);
        assertThat(decision.secondary()).isNull();
    }

    @Test
    void shouldRouteOrderAndSupportForMixedQuestion() {
        AssistantRouteDecision decision = supervisor.route("为什么我的订单被取消了");

        assertThat(decision.primary()).isEqualTo(AssistantSpecialistType.ORDER_SERVICE);
        assertThat(decision.secondary()).isEqualTo(AssistantSpecialistType.CUSTOMER_SUPPORT);
    }

    @Test
    void shouldFallbackToSupportForRuleQuestion() {
        AssistantRouteDecision decision = supervisor.route("签约流程是什么");

        assertThat(decision.primary()).isEqualTo(AssistantSpecialistType.CUSTOMER_SUPPORT);
    }
}
