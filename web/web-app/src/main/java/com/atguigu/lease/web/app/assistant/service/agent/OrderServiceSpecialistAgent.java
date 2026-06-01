package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.chat.AssistantPromptService;

import java.util.List;

public class OrderServiceSpecialistAgent extends AbstractSpecialistAgent {

    public OrderServiceSpecialistAgent(AgentChatClientFactory chatClientFactory,
                                       AssistantPromptService promptService,
                                       AssistantSkillTemplateService skillTemplateService) {
        super(chatClientFactory, promptService, skillTemplateService);
    }

    @Override
    public SpecialistAgentType type() {
        return SpecialistAgentType.ORDER_SERVICE;
    }

    @Override
    protected String skillName() {
        return "order-service";
    }

    @Override
    protected String roleInstructions() {
        return """
                你当前扮演订单服务 Agent。
                - 你的核心目标是查询、解释和处理与签约订单、支付、取消、租约相关的问题。
                - 涉及实时订单状态时，优先调用订单工具，不得编造状态、金额、日期或编号。
                - 如果用户问题同时带有规则解释色彩，也先给出实时状态，再提示相关规则说明。
                - 回答结构固定为：当前状态/结果 -> 关键原因或说明 -> 下一步建议。
                - 除非用户明确要求且工具支持，否则不要主动执行高风险动作。
                """;
    }

    @Override
    protected List<AssistantNextAction> defaultNextActions() {
        return List.of(
                new AssistantNextAction("查看订单", "帮我查我的订单"),
                new AssistantNextAction("订单详情", "帮我查看最近一笔签约订单详情"),
                new AssistantNextAction("签约流程", "看完房之后下一步怎么签约")
        );
    }
}
