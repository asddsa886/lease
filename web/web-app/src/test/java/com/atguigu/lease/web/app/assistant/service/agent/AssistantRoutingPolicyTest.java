package com.atguigu.lease.web.app.assistant.service.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantRoutingPolicyTest {

    private final AssistantRoutingPolicy routingPolicy = new AssistantRoutingPolicy();

    @Test
    void shouldRouteRoomSearchRequests() {
        assertThat(routingPolicy.classify("帮我查北京 3000 以内的房源"))
                .isEqualTo(AssistantAgentRoute.ROOM_SEARCH);
    }

    @Test
    void shouldRouteAppointmentRequests() {
        assertThat(routingPolicy.classify("帮我把预约改到周六下午三点"))
                .isEqualTo(AssistantAgentRoute.APPOINTMENT);
    }

    @Test
    void shouldRouteLeaseOrderRequests() {
        assertThat(routingPolicy.classify("帮我看看我的签约订单"))
                .isEqualTo(AssistantAgentRoute.LEASE_ORDER);
    }

    @Test
    void shouldRouteCompositeWorkflowRequests() {
        assertThat(routingPolicy.classify("帮我找北京 3000 到 4000 的房子，挑三套最合适的，再帮我约周六下午看房"))
                .isEqualTo(AssistantAgentRoute.RENTAL_WORKFLOW);
    }
}
