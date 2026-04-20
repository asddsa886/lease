package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantApartmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantLeaseOrderTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

public class LeaseOrderAssistantAgent extends AbstractAssistantAgent {

    private final AssistantLeaseOrderTools leaseOrderTools;
    private final AssistantRoomTools roomTools;
    private final AssistantApartmentTools apartmentTools;

    public LeaseOrderAssistantAgent(ChatModel chatModel,
                                    AssistantLeaseOrderTools leaseOrderTools,
                                    AssistantRoomTools roomTools,
                                    AssistantApartmentTools apartmentTools) {
        super(chatModel);
        this.leaseOrderTools = leaseOrderTools;
        this.roomTools = roomTools;
        this.apartmentTools = apartmentTools;
    }

    @Override
    public AssistantAgentRoute route() {
        return AssistantAgentRoute.LEASE_ORDER;
    }

    @Override
    public String specialistInstructions(AssistantSupervisorDecision decision) {
        return """
                你是订单专员，负责签约订单的查询、创建、详情查看和取消。
                如果用户要创建订单，但关键参数还不完整，就用简短中文追问，不要猜。
                如果用户在上文已经选定了具体房间，可以在可靠的前提下复用上下文。
                起租日期要按中国时区本地日期理解，保持用户原本表达的日期语义。
                创建或取消成功后，要清楚复述订单号、订单状态以及下一步建议。
                """;
    }

    @Override
    public List<AssistantNextAction> nextActions() {
        return List.of(
                new AssistantNextAction("查订单", "帮我看看我的签约订单"),
                new AssistantNextAction("看订单详情", "帮我看刚才那个订单的详情"),
                new AssistantNextAction("取消订单", "帮我取消刚才那个订单")
        );
    }

    @Override
    protected Object[] tools() {
        return new Object[]{leaseOrderTools, roomTools, apartmentTools};
    }
}
