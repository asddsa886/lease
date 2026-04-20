package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantApartmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantAppointmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantKnowledgeTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantLeaseOrderTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

public class RentalWorkflowAssistantAgent extends AbstractAssistantAgent {

    private final AssistantApartmentTools apartmentTools;
    private final AssistantRoomTools roomTools;
    private final AssistantAppointmentTools appointmentTools;
    private final AssistantLeaseOrderTools leaseOrderTools;
    private final AssistantKnowledgeTools knowledgeTools;

    public RentalWorkflowAssistantAgent(ChatModel chatModel,
                                        AssistantApartmentTools apartmentTools,
                                        AssistantRoomTools roomTools,
                                        AssistantAppointmentTools appointmentTools,
                                        AssistantLeaseOrderTools leaseOrderTools,
                                        AssistantKnowledgeTools knowledgeTools) {
        super(chatModel);
        this.apartmentTools = apartmentTools;
        this.roomTools = roomTools;
        this.appointmentTools = appointmentTools;
        this.leaseOrderTools = leaseOrderTools;
        this.knowledgeTools = knowledgeTools;
    }

    @Override
    public AssistantAgentRoute route() {
        return AssistantAgentRoute.RENTAL_WORKFLOW;
    }

    @Override
    public String specialistInstructions(AssistantSupervisorDecision decision) {
        return """
                你是租房流程编排专员，负责处理跨阶段、多步骤的租房任务。

                规则：
                1. 搜索、比较、推荐这类只读步骤可以直接执行。
                2. 创建预约、创建订单这类写操作，只有在目标公寓/房间以及时间/日期都明确时才能执行。
                3. 如果用户说“先找房再帮我预约”或“挑三套再帮我下单”，先给推荐结果，再让用户确认具体编号。
                4. 推荐结果尽量给出编号，并附带 roomId 或 apartmentId，方便下一步衔接。
                5. 回复里要清楚说明：已经完成了什么、还缺什么、用户下一步要确认什么。
                6. 预约时间和起租日期都按中国时区本地语义理解，不能把“明天下午12点”这种表达转换成 UTC。
                """;
    }

    @Override
    public List<AssistantNextAction> nextActions() {
        return List.of(
                new AssistantNextAction("继续找房", "帮我再推荐三套更合适的"),
                new AssistantNextAction("创建预约", "帮我预约看第一个房源"),
                new AssistantNextAction("创建订单", "如果合适就帮我创建签约订单")
        );
    }

    @Override
    protected Object[] tools() {
        return new Object[]{apartmentTools, roomTools, appointmentTools, leaseOrderTools, knowledgeTools};
    }
}
