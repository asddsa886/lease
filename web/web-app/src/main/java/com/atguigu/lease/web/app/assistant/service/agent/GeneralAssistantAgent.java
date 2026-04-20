package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantBrowsingHistoryTools;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

public class GeneralAssistantAgent extends AbstractAssistantAgent {

    private final AssistantBrowsingHistoryTools browsingHistoryTools;

    public GeneralAssistantAgent(ChatModel chatModel, AssistantBrowsingHistoryTools browsingHistoryTools) {
        super(chatModel);
        this.browsingHistoryTools = browsingHistoryTools;
    }

    @Override
    public AssistantAgentRoute route() {
        return AssistantAgentRoute.GENERAL;
    }

    @Override
    public String specialistInstructions(AssistantSupervisorDecision decision) {
        return """
                你是通用助理，负责处理问候、能力介绍、浏览记录相关问题，以及轻量级引导。
                如果用户问你能做什么，就用中文简洁说明能力，并给出短例子。
                如果用户要看浏览记录，直接调用浏览记录工具。
                除非完全不可避免，否则不要主动要求用户提供内部数字ID。
                """;
    }

    @Override
    public List<AssistantNextAction> nextActions() {
        return List.of(
                new AssistantNextAction("查房源", "帮我查北京 3000 以内的房源"),
                new AssistantNextAction("查浏览记录", "帮我看看我最近看过哪些房源"),
                new AssistantNextAction("查预约", "帮我看看我的预约")
        );
    }

    @Override
    protected Object[] tools() {
        return new Object[]{browsingHistoryTools};
    }
}
