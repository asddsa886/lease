package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantApartmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantAppointmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantKnowledgeTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

public class AppointmentAssistantAgent extends AbstractAssistantAgent {

    private final AssistantAppointmentTools appointmentTools;
    private final AssistantApartmentTools apartmentTools;
    private final AssistantRoomTools roomTools;
    private final AssistantKnowledgeTools knowledgeTools;

    public AppointmentAssistantAgent(ChatModel chatModel,
                                     AssistantAppointmentTools appointmentTools,
                                     AssistantApartmentTools apartmentTools,
                                     AssistantRoomTools roomTools,
                                     AssistantKnowledgeTools knowledgeTools) {
        super(chatModel);
        this.appointmentTools = appointmentTools;
        this.apartmentTools = apartmentTools;
        this.roomTools = roomTools;
        this.knowledgeTools = knowledgeTools;
    }

    @Override
    public AssistantAgentRoute route() {
        return AssistantAgentRoute.APPOINTMENT;
    }

    @Override
    public String specialistInstructions(AssistantSupervisorDecision decision) {
        return """
                你是预约专员，负责预约列表、创建预约、取消预约和修改预约。
                只在真正缺少关键信息时再追问，并且一次只问最少的问题。
                如果用户说“第一个”“刚才那个”这类代词，先尽量结合上下文解析；如果不可靠，再让用户补充具体公寓或预约。
                所有预约时间都按中国时区本地时间理解。
                如果用户说“明天下午12点”，传给工具时也必须保持这个本地语义，必要时可以规范成 12:00:00，但绝不能改成 04:00:00 或 UTC 时间。
                每次成功创建、取消或改期后，都要用中文明确复述预约ID、预约时间和下一步建议。
                """;
    }

    @Override
    public List<AssistantNextAction> nextActions() {
        return List.of(
                new AssistantNextAction("查预约", "帮我看看我的预约"),
                new AssistantNextAction("改约", "帮我把刚才的预约改到周六下午三点"),
                new AssistantNextAction("取消预约", "帮我取消刚才的预约")
        );
    }

    @Override
    protected Object[] tools() {
        return new Object[]{appointmentTools, apartmentTools, roomTools, knowledgeTools};
    }
}
