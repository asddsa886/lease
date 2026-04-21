package com.atguigu.lease.web.app.assistant.config;

import com.atguigu.lease.web.app.assistant.service.chat.AppAssistantService;
import com.atguigu.lease.web.app.assistant.service.chat.AssistantPromptService;
import com.atguigu.lease.web.app.assistant.service.chat.DisabledAssistantService;
import com.atguigu.lease.web.app.assistant.service.chat.OfficialSkillsAssistantService;
import com.atguigu.lease.web.app.assistant.service.memory.AssistantLongTermMemoryService;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationSessionService;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantApartmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantAppointmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantBrowsingHistoryTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantKnowledgeTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantLeaseOrderTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantConfiguration {

    @Bean
    @ConditionalOnMissingBean(AppAssistantService.class)
    public AppAssistantService appAssistantService(ObjectProvider<ChatModel> chatModelProvider,
                                                   AssistantPromptService promptService,
                                                   AssistantConversationSessionService conversationSessionService,
                                                   AssistantProperties assistantProperties,
                                                   AssistantApartmentTools apartmentTools,
                                                   AssistantRoomTools roomTools,
                                                   AssistantBrowsingHistoryTools browsingHistoryTools,
                                                   AssistantAppointmentTools appointmentTools,
                                                   AssistantLeaseOrderTools leaseOrderTools,
                                                   AssistantKnowledgeTools knowledgeTools,
                                                   AssistantLongTermMemoryService longTermMemoryService,
                                                   SkillPromptAugmentAdvisor skillPromptAugmentAdvisor,
                                                   @Qualifier("assistantReadSkillToolCallback") ToolCallback readSkillToolCallback) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (assistantProperties.isEnabled() && chatModel != null) {
            return new OfficialSkillsAssistantService(
                    chatModel,
                    promptService,
                    conversationSessionService,
                    assistantProperties,
                    apartmentTools,
                    roomTools,
                    browsingHistoryTools,
                    appointmentTools,
                    leaseOrderTools,
                    knowledgeTools,
                    longTermMemoryService,
                    skillPromptAugmentAdvisor,
                    readSkillToolCallback
            );
        }
        return new DisabledAssistantService(promptService);
    }
}
