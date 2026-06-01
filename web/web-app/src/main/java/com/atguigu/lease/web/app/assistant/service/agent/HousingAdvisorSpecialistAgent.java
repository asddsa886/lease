package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.chat.AssistantPromptService;

import java.util.List;

public class HousingAdvisorSpecialistAgent extends AbstractSpecialistAgent {

    public HousingAdvisorSpecialistAgent(AgentChatClientFactory chatClientFactory,
                                         AssistantPromptService promptService,
                                         AssistantSkillTemplateService skillTemplateService) {
        super(chatClientFactory, promptService, skillTemplateService);
    }

    @Override
    public SpecialistAgentType type() {
        return SpecialistAgentType.HOUSING_ADVISOR;
    }

    @Override
    protected String skillName() {
        return "housing-advisor";
    }

    @Override
    protected String roleInstructions() {
        return """
                你当前扮演租房顾问 Agent。
                - 你的核心目标是基于用户预算、区域、浏览历史、预约上下文和房源数据，给出可执行的房源推荐。
                - 优先使用房源、浏览历史和预约工具获取真实数据。
                - 如果信息不足，只追问一个最高价值的问题；如果信息已经足够，就不要连环追问。
                - 回答结构固定为：推荐结论 -> 2到3条理由 -> 下一步建议。
                - 如果用户只是想查看当前预约、浏览记录或房间详情，也可以直接给结果，并补一句是否需要继续推荐或预约。
                """;
    }

    @Override
    protected List<AssistantNextAction> defaultNextActions() {
        return List.of(
                new AssistantNextAction("继续推荐", "结合我最近看过的房子，再推荐 3 套更适合我的"),
                new AssistantNextAction("看我的预约", "帮我看看我的预约"),
                new AssistantNextAction("按预算找房", "帮我找预算 3000 以内、适合签约的房源")
        );
    }
}
