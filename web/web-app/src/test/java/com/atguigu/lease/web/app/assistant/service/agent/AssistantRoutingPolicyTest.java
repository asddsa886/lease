package com.atguigu.lease.web.app.assistant.service.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantRoutingPolicyTest {

    private final AssistantRoutingPolicy routingPolicy = new AssistantRoutingPolicy();

    @Test
    void shouldRouteSearchRequestsToSearchQa() {
        assertThat(routingPolicy.classify("帮我查北京3000以内的房源"))
                .isEqualTo(AssistantAgentRoute.SEARCH_QA);
    }

    @Test
    void shouldRouteAppointmentRequestsToBusinessExecution() {
        assertThat(routingPolicy.classify("帮我把预约改到周六下午三点"))
                .isEqualTo(AssistantAgentRoute.BUSINESS_EXECUTION);
    }

    @Test
    void shouldRouteLeaseOrderRequestsToBusinessExecution() {
        assertThat(routingPolicy.classify("帮我看看我的签约订单"))
                .isEqualTo(AssistantAgentRoute.BUSINESS_EXECUTION);
    }

    @Test
    void shouldRouteCompositeBusinessRequestsToBusinessExecution() {
        assertThat(routingPolicy.classify("帮我先找房再预约看房"))
                .isEqualTo(AssistantAgentRoute.BUSINESS_EXECUTION);
    }
}
