package com.atguigu.lease.web.app.assistant.service.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class SupervisorPlanValidator {

    public ValidatedSupervisorPlan validate(SupervisorPlan plan, String userMessage) {
        if (plan == null) {
            throw new IllegalArgumentException("Supervisor plan is required");
        }

        SpecialistAgentType primaryAgentType = SpecialistAgentType.fromPlanName(plan.getPrimaryAgent());
        if (primaryAgentType == null) {
            throw new IllegalArgumentException("Supervisor primary agent is invalid");
        }

        List<SpecialistAgentType> orderedAgents = new ArrayList<>();
        orderedAgents.add(primaryAgentType);
        Set<SpecialistAgentType> seen = new LinkedHashSet<>(orderedAgents);

        for (String item : plan.getAdditionalAgents()) {
            SpecialistAgentType additionalType = SpecialistAgentType.fromPlanName(item);
            if (additionalType == null) {
                throw new IllegalArgumentException("Supervisor additional agent is invalid");
            }
            if (!seen.add(additionalType)) {
                throw new IllegalArgumentException("Supervisor plan contains duplicate agents");
            }
            orderedAgents.add(additionalType);
        }

        if (orderedAgents.size() > 3) {
            throw new IllegalArgumentException("Supervisor plan contains too many agents");
        }

        if (requiresOrderAgent(userMessage) && !seen.contains(SpecialistAgentType.ORDER_SERVICE)) {
            throw new IllegalArgumentException("Order-sensitive request must include order-service agent");
        }

        if (requiresHousingAgent(userMessage) && !seen.contains(SpecialistAgentType.HOUSING_ADVISOR)) {
            throw new IllegalArgumentException("Housing-sensitive request must include housing-advisor agent");
        }

        SupervisorClarification clarification = null;
        if (plan.isNeedsClarification()) {
            String question = plan.getClarificationQuestion();
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("Clarification question is missing");
            }
            clarification = new SupervisorClarification(primaryAgentType, question.trim());
        }

        List<SupervisorPlanStep> steps = orderedAgents.stream()
                .map(agentType -> new SupervisorPlanStep(agentType, resolveObjective(agentType, plan.getGoal())))
                .toList();

        return new ValidatedSupervisorPlan(primaryAgentType, orderedAgents, steps, clarification);
    }

    private boolean requiresOrderAgent(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String normalized = userMessage.trim().replace(" ", "").toLowerCase(Locale.ROOT);
        return containsAny(normalized,
                "订单", "签约订单", "取消订单", "支付订单", "订单状态", "租约状态", "我的订单");
    }

    private boolean requiresHousingAgent(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String normalized = userMessage.trim().replace(" ", "").toLowerCase(Locale.ROOT);
        return containsAny(normalized,
                "房源", "房间", "推荐", "对比", "收藏", "租金", "公寓",
                "room", "housing", "recommend", "compare", "favorite");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String resolveObjective(SpecialistAgentType agentType, String goal) {
        if (goal != null && !goal.isBlank()) {
            return goal.trim();
        }
        return switch (agentType) {
            case HOUSING_ADVISOR -> "基于用户需求给出房源推荐或选房建议";
            case ORDER_SERVICE -> "查询并解释用户的签约订单、支付或租约相关状态";
            case CUSTOMER_SUPPORT -> "解释平台规则、流程说明或常见问题";
        };
    }

    public record ValidatedSupervisorPlan(
            SpecialistAgentType primaryAgentType,
            List<SpecialistAgentType> orderedAgents,
            List<SupervisorPlanStep> steps,
            SupervisorClarification clarification
    ) {
    }
}
