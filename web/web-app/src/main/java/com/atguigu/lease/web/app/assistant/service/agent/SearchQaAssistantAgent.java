package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantApartmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantBrowsingHistoryTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantKnowledgeTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

public class SearchQaAssistantAgent extends AbstractAssistantAgent {

    private final AssistantApartmentTools apartmentTools;
    private final AssistantRoomTools roomTools;
    private final AssistantBrowsingHistoryTools browsingHistoryTools;
    private final AssistantKnowledgeTools knowledgeTools;

    public SearchQaAssistantAgent(ChatModel chatModel,
                                  AssistantApartmentTools apartmentTools,
                                  AssistantRoomTools roomTools,
                                  AssistantBrowsingHistoryTools browsingHistoryTools,
                                  AssistantKnowledgeTools knowledgeTools) {
        super(chatModel);
        this.apartmentTools = apartmentTools;
        this.roomTools = roomTools;
        this.browsingHistoryTools = browsingHistoryTools;
        this.knowledgeTools = knowledgeTools;
    }

    @Override
    public AssistantAgentRoute route() {
        return AssistantAgentRoute.SEARCH_QA;
    }

    @Override
    public String specialistInstructions(AssistantSupervisorDecision decision) {
        return """
                你是“检索问答专员”，优先处理：
                1. 房源检索、筛选、比较和推荐；
                2. 平台规则问答、流程说明、租房常见问题；
                3. 浏览记录查询和轻量咨询。

                规则：
                - 优先调用工具拿真实数据，不编造房源、价格、状态、ID。
                - 用户未给完整条件时，先基于已有条件给出初步结果，再补问最少必要信息。
                - 涉及创建/取消/改约/下单等执行动作时，明确提示将交由业务执行专员继续处理。
                """;
    }

    @Override
    public List<AssistantNextAction> nextActions() {
        return List.of(
                new AssistantNextAction("查房源", "帮我查北京3000以内的房源"),
                new AssistantNextAction("看浏览记录", "帮我看看我最近浏览过哪些房源"),
                new AssistantNextAction("问规则", "签约订单超时会怎么处理")
        );
    }

    @Override
    protected Object[] tools() {
        return new Object[]{apartmentTools, roomTools, browsingHistoryTools, knowledgeTools};
    }
}
