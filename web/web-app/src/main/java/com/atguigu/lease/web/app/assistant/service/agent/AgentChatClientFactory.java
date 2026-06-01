package com.atguigu.lease.web.app.assistant.service.agent;

import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantAppointmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantBrowsingHistoryTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantKnowledgeTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantLeaseOrderTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.EnumMap;
import java.util.Map;

public class AgentChatClientFactory {

    private final ChatModel chatModel;
    private final SkillPromptAugmentAdvisor skillPromptAugmentAdvisor;
    private final ToolCallback readSkillToolCallback;
    private final AssistantRoomTools roomTools;
    private final AssistantBrowsingHistoryTools browsingHistoryTools;
    private final AssistantAppointmentTools appointmentTools;
    private final AssistantLeaseOrderTools leaseOrderTools;
    private final AssistantKnowledgeTools knowledgeTools;
    private final Map<SpecialistAgentType, ChatClient> cache = new EnumMap<>(SpecialistAgentType.class);

    public AgentChatClientFactory(ChatModel chatModel,
                                  SkillPromptAugmentAdvisor skillPromptAugmentAdvisor,
                                  ToolCallback readSkillToolCallback,
                                  AssistantRoomTools roomTools,
                                  AssistantBrowsingHistoryTools browsingHistoryTools,
                                  AssistantAppointmentTools appointmentTools,
                                  AssistantLeaseOrderTools leaseOrderTools,
                                  AssistantKnowledgeTools knowledgeTools) {
        this.chatModel = chatModel;
        this.skillPromptAugmentAdvisor = skillPromptAugmentAdvisor;
        this.readSkillToolCallback = readSkillToolCallback;
        this.roomTools = roomTools;
        this.browsingHistoryTools = browsingHistoryTools;
        this.appointmentTools = appointmentTools;
        this.leaseOrderTools = leaseOrderTools;
        this.knowledgeTools = knowledgeTools;
    }

    public ChatClient getClient(SpecialistAgentType agentType) {
        return cache.computeIfAbsent(agentType, this::buildClient);
    }

    private ChatClient buildClient(SpecialistAgentType agentType) {
        Object[] tools = switch (agentType) {
            case HOUSING_ADVISOR -> new Object[]{
                    roomTools,
                    browsingHistoryTools,
                    appointmentTools,
                    knowledgeTools
            };
            case ORDER_SERVICE -> new Object[]{
                    leaseOrderTools,
                    appointmentTools,
                    knowledgeTools
            };
            case CUSTOMER_SUPPORT -> new Object[]{
                    knowledgeTools,
                    leaseOrderTools,
                    appointmentTools
            };
        };
        return ChatClient.builder(chatModel)
                .defaultAdvisors(skillPromptAugmentAdvisor)
                .defaultTools(tools)
                .defaultToolCallbacks(readSkillToolCallback)
                .build();
    }
}
