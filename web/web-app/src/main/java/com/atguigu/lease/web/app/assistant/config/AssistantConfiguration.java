package com.atguigu.lease.web.app.assistant.config;

import com.atguigu.lease.web.app.assistant.service.chat.AppAssistantService;
import com.atguigu.lease.web.app.assistant.service.chat.AssistantPromptService;
import com.atguigu.lease.web.app.assistant.service.chat.DisabledAssistantService;
import com.atguigu.lease.web.app.assistant.service.chat.OfficialSkillsAssistantService;
import com.atguigu.lease.web.app.assistant.service.chat.SupervisorAgentAssistantService;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantRoutingSupervisor;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantSkillTemplateService;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantSpecialist;
import com.atguigu.lease.web.app.assistant.service.agent.CustomerSupportSpecialist;
import com.atguigu.lease.web.app.assistant.service.agent.HousingAdvisorSpecialist;
import com.atguigu.lease.web.app.assistant.service.agent.OrderServiceSpecialist;
import com.atguigu.lease.web.app.assistant.service.memory.RedisAssistantLongTermMemoryService;
import com.atguigu.lease.web.app.assistant.service.session.RedisAssistantConversationSessionService;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantAppointmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantBrowsingHistoryTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantKnowledgeTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantLeaseOrderTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ResourceLoader;

import java.util.List;

@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantConfiguration {

    @Bean
    @ConditionalOnMissingBean(AppAssistantService.class)
    public AppAssistantService appAssistantService(ObjectProvider<ChatModel> chatModelProvider,
                                                   AssistantPromptService promptService,
                                                   RedisAssistantConversationSessionService conversationSessionService,
                                                   AssistantProperties assistantProperties,
                                                   RedisAssistantLongTermMemoryService longTermMemoryService,
                                                   AssistantRoutingSupervisor routingSupervisor,
                                                   AssistantRoomTools roomTools,
                                                   AssistantBrowsingHistoryTools browsingHistoryTools,
                                                   AssistantAppointmentTools appointmentTools,
                                                   AssistantLeaseOrderTools leaseOrderTools,
                                                   AssistantKnowledgeTools knowledgeTools,
                                                   SkillPromptAugmentAdvisor skillPromptAugmentAdvisor,
                                                   @Qualifier("assistantReadSkillToolCallback") ToolCallback readSkillToolCallback,
                                                   ObjectProvider<List<AssistantSpecialist>> specialistsProvider) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (assistantProperties.isEnabled() && chatModel != null) {
            List<AssistantSpecialist> specialists = specialistsProvider.getIfAvailable(List::of);
            AppAssistantService legacyAssistantService = buildLegacyOfficialSkillsAssistantService(
                    chatModel,
                    promptService,
                    conversationSessionService,
                    assistantProperties,
                    roomTools,
                    browsingHistoryTools,
                    appointmentTools,
                    leaseOrderTools,
                    knowledgeTools,
                    longTermMemoryService,
                    skillPromptAugmentAdvisor,
                    readSkillToolCallback
            );
            return new SupervisorAgentAssistantService(
                    promptService,
                    conversationSessionService,
                    longTermMemoryService,
                    assistantProperties,
                    routingSupervisor,
                    specialists,
                    legacyAssistantService
            );
        }
        return new DisabledAssistantService(promptService);
    }

    private AppAssistantService buildLegacyOfficialSkillsAssistantService(ChatModel chatModel,
                                                                          AssistantPromptService promptService,
                                                                          RedisAssistantConversationSessionService conversationSessionService,
                                                                          AssistantProperties assistantProperties,
                                                                          AssistantRoomTools roomTools,
                                                                          AssistantBrowsingHistoryTools browsingHistoryTools,
                                                                          AssistantAppointmentTools appointmentTools,
                                                                          AssistantLeaseOrderTools leaseOrderTools,
                                                                          AssistantKnowledgeTools knowledgeTools,
                                                                          RedisAssistantLongTermMemoryService longTermMemoryService,
                                                                          SkillPromptAugmentAdvisor skillPromptAugmentAdvisor,
                                                                          ToolCallback readSkillToolCallback) {
        return new OfficialSkillsAssistantService(
                chatModel,
                promptService,
                conversationSessionService,
                assistantProperties,
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

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public ChatClient assistantChatClient(ChatModel chatModel,
                                          AssistantRoomTools roomTools,
                                          AssistantBrowsingHistoryTools browsingHistoryTools,
                                          AssistantAppointmentTools appointmentTools,
                                          AssistantLeaseOrderTools leaseOrderTools,
                                          AssistantKnowledgeTools knowledgeTools,
                                          SkillPromptAugmentAdvisor skillPromptAugmentAdvisor,
                                          @Qualifier("assistantReadSkillToolCallback") ToolCallback readSkillToolCallback) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(skillPromptAugmentAdvisor)
                .defaultTools(
                        roomTools,
                        browsingHistoryTools,
                        appointmentTools,
                        leaseOrderTools,
                        knowledgeTools
                )
                .defaultToolCallbacks(readSkillToolCallback)
                .build();
    }

    @Bean
    public AssistantRoutingSupervisor assistantRoutingSupervisor() {
        return new AssistantRoutingSupervisor();
    }

    @Bean
    public AssistantSkillTemplateService assistantSkillTemplateService(ResourceLoader resourceLoader) {
        return new AssistantSkillTemplateService(resourceLoader);
    }

    @Bean
    @ConditionalOnBean(ChatClient.class)
    public AssistantSpecialist housingAdvisorSpecialist(ChatClient assistantChatClient,
                                                        AssistantPromptService promptService,
                                                        AssistantSkillTemplateService assistantSkillTemplateService) {
        return new HousingAdvisorSpecialist(assistantChatClient, promptService, assistantSkillTemplateService);
    }

    @Bean
    @ConditionalOnBean(ChatClient.class)
    public AssistantSpecialist orderServiceSpecialist(ChatClient assistantChatClient,
                                                      AssistantPromptService promptService,
                                                      AssistantSkillTemplateService assistantSkillTemplateService) {
        return new OrderServiceSpecialist(assistantChatClient, promptService, assistantSkillTemplateService);
    }

    @Bean
    @ConditionalOnBean(ChatClient.class)
    public AssistantSpecialist customerSupportSpecialist(ChatClient assistantChatClient,
                                                         AssistantPromptService promptService,
                                                         AssistantSkillTemplateService assistantSkillTemplateService) {
        return new CustomerSupportSpecialist(assistantChatClient, promptService, assistantSkillTemplateService);
    }
}
