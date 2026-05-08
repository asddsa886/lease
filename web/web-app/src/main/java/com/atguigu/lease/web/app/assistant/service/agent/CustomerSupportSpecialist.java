package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.chat.AssistantPromptService;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

public class CustomerSupportSpecialist extends AbstractRoleBoundAssistantSpecialist {

    public CustomerSupportSpecialist(ChatClient chatClient,
                                     AssistantPromptService promptService,
                                     AssistantSkillTemplateService skillTemplateService) {
        super(chatClient, promptService, skillTemplateService);
    }

    @Override
    public AssistantSpecialistType type() {
        return AssistantSpecialistType.CUSTOMER_SUPPORT;
    }

    @Override
    protected String skillName() {
        return "customer-support";
    }

    @Override
    protected String roleInstructions() {
        return """
                你当前扮演客服说明专员。
                - 你的核心目标是解释平台规则、签约流程、预约说明、订单超时与常见问题。
                - 优先使用知识库检索，不要把规则说明伪装成实时业务状态。
                - 如果用户问题同时提到当前订单或预约状态，可以在说明前补充真实上下文，但最终重点仍是规则解释和流程引导。
                - 回答结构固定为：结论 -> 简短依据/步骤 -> 下一步建议。
                - 当知识库没有足够信息时，要明确说明信息不足，不要编造平台政策。
                """;
    }

    @Override
    protected List<AssistantNextAction> defaultNextActions() {
        return List.of(
                new AssistantNextAction("签约流程", "看完房之后下一步怎么签约"),
                new AssistantNextAction("预约说明", "帮我介绍一下预约看房流程"),
                new AssistantNextAction("订单规则", "为什么我的订单会被取消")
        );
    }
}
