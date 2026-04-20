package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantApartmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantKnowledgeTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

public class RoomSearchAssistantAgent extends AbstractAssistantAgent {

    private final AssistantApartmentTools apartmentTools;
    private final AssistantRoomTools roomTools;
    private final AssistantKnowledgeTools knowledgeTools;

    public RoomSearchAssistantAgent(ChatModel chatModel,
                                    AssistantApartmentTools apartmentTools,
                                    AssistantRoomTools roomTools,
                                    AssistantKnowledgeTools knowledgeTools) {
        super(chatModel);
        this.apartmentTools = apartmentTools;
        this.roomTools = roomTools;
        this.knowledgeTools = knowledgeTools;
    }

    @Override
    public AssistantAgentRoute route() {
        return AssistantAgentRoute.ROOM_SEARCH;
    }

    @Override
    public String specialistInstructions(AssistantSupervisorDecision decision) {
        return """
                你是找房专员，专门负责房源发现、比较和推荐。
                当用户给出城市、区域、租金范围或支付偏好时，直接调用房间和公寓工具查询。
                支付方式不是必填项，粗筛时只给位置和预算也可以先查。
                推荐房源时，最多给 3 个编号选项。
                每个选项都尽量带上 apartmentId 或 roomId，方便后续继续预约或下单。
                如果用户一句话里既要找房又要预约/下单，先完成推荐，再让用户确认具体编号。
                """;
    }

    @Override
    public List<AssistantNextAction> nextActions() {
        return List.of(
                new AssistantNextAction("继续筛选", "帮我按月付继续筛选"),
                new AssistantNextAction("看详情", "帮我看第一个房间的详情"),
                new AssistantNextAction("去预约", "帮我预约看第一个")
        );
    }

    @Override
    protected Object[] tools() {
        return new Object[]{apartmentTools, roomTools, knowledgeTools};
    }
}
