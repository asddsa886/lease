package com.atguigu.lease.web.app.assistant.service.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisorPlanValidatorTest {

    private final SupervisorPlanValidator validator = new SupervisorPlanValidator();

    @Test
    void shouldValidateSingleAdvisorPlan() {
        SupervisorPlan plan = new SupervisorPlan();
        plan.setPrimaryAgent("housing-advisor");
        plan.setGoal("推荐最适合签约的房源");

        SupervisorPlanValidator.ValidatedSupervisorPlan validated = validator.validate(
                plan,
                "我预算3000，在朝阳，推荐最适合签约的三个房间"
        );

        assertThat(validated.primaryAgentType()).isEqualTo(SpecialistAgentType.HOUSING_ADVISOR);
        assertThat(validated.orderedAgents()).containsExactly(SpecialistAgentType.HOUSING_ADVISOR);
    }

    @Test
    void shouldValidateOrderAndSupportPlan() {
        SupervisorPlan plan = new SupervisorPlan();
        plan.setPrimaryAgent("order-service");
        plan.setAdditionalAgents(List.of("customer-support"));
        plan.setGoal("先解释订单状态，再补充规则说明");

        SupervisorPlanValidator.ValidatedSupervisorPlan validated = validator.validate(
                plan,
                "为什么我的订单被取消了"
        );

        assertThat(validated.primaryAgentType()).isEqualTo(SpecialistAgentType.ORDER_SERVICE);
        assertThat(validated.orderedAgents()).containsExactly(
                SpecialistAgentType.ORDER_SERVICE,
                SpecialistAgentType.CUSTOMER_SUPPORT
        );
    }

    @Test
    void shouldRejectDuplicateAgents() {
        SupervisorPlan plan = new SupervisorPlan();
        plan.setPrimaryAgent("order-service");
        plan.setAdditionalAgents(List.of("order-service"));

        assertThatThrownBy(() -> validator.validate(plan, "帮我查我的订单"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void shouldRejectOrderRequestWithoutOrderAgent() {
        SupervisorPlan plan = new SupervisorPlan();
        plan.setPrimaryAgent("customer-support");
        plan.setGoal("解释为什么订单被取消");

        assertThatThrownBy(() -> validator.validate(plan, "为什么我的订单被取消了"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Order-sensitive");
    }
}
