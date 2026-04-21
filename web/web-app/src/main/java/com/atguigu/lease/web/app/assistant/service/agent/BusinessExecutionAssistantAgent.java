package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantApartmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantAppointmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantKnowledgeTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantLeaseOrderTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

public class BusinessExecutionAssistantAgent extends AbstractAssistantAgent {

    private final AssistantAppointmentTools appointmentTools;
    private final AssistantLeaseOrderTools leaseOrderTools;
    private final AssistantApartmentTools apartmentTools;
    private final AssistantRoomTools roomTools;
    private final AssistantKnowledgeTools knowledgeTools;

    public BusinessExecutionAssistantAgent(ChatModel chatModel,
                                           AssistantAppointmentTools appointmentTools,
                                           AssistantLeaseOrderTools leaseOrderTools,
                                           AssistantApartmentTools apartmentTools,
                                           AssistantRoomTools roomTools,
                                           AssistantKnowledgeTools knowledgeTools) {
        super(chatModel);
        this.appointmentTools = appointmentTools;
        this.leaseOrderTools = leaseOrderTools;
        this.apartmentTools = apartmentTools;
        this.roomTools = roomTools;
        this.knowledgeTools = knowledgeTools;
    }

    @Override
    public AssistantAgentRoute route() {
        return AssistantAgentRoute.BUSINESS_EXECUTION;
    }

    @Override
    public String specialistInstructions(AssistantSupervisorDecision decision) {
        return """
                你是“业务执行专员”，负责预约和签约订单相关动作。
                你处理的任务包括：
                - 预约查询、创建、取消、改期；
                - 签约订单查询、创建、取消；
                - 与上述动作直接相关的信息确认。

                规则：
                - 仅在关键信息缺失时追问，并且一次只追问最少信息。
                - 涉及日期和时间时，统一按中国时区本地语义理解，不能转成UTC表达。
                - 动作执行后，必须明确返回执行结果（成功/失败）、关键编号和下一步建议。
                - 若用户先要选房再执行动作，先调用查询工具给出选项，再引导确认具体目标。
                """;
    }

    @Override
    public List<AssistantNextAction> nextActions() {
        return List.of(
                new AssistantNextAction("看我的预约", "帮我看看我的预约记录"),
                new AssistantNextAction("改预约时间", "帮我把刚才那个预约改到周六下午三点"),
                new AssistantNextAction("看签约订单", "帮我看看我的签约订单")
        );
    }

    @Override
    protected Object[] tools() {
        return new Object[]{appointmentTools, leaseOrderTools, apartmentTools, roomTools, knowledgeTools};
    }
}
