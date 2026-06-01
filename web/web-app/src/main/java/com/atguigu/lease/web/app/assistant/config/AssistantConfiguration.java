package com.atguigu.lease.web.app.assistant.config;

import com.atguigu.lease.web.app.assistant.service.chat.AppAssistantService;
import com.atguigu.lease.web.app.assistant.service.chat.AssistantPromptService;
import com.atguigu.lease.web.app.assistant.service.chat.DisabledAssistantService;
import com.atguigu.lease.web.app.assistant.service.chat.OfficialSkillsAssistantService;
import com.atguigu.lease.web.app.assistant.service.chat.SupervisorAgentAssistantService;
import com.atguigu.lease.web.app.assistant.service.agent.AgentChatClientFactory;
import com.atguigu.lease.web.app.assistant.service.agent.AssistantSkillTemplateService;
import com.atguigu.lease.web.app.assistant.service.agent.CustomerSupportSpecialistAgent;
import com.atguigu.lease.web.app.assistant.service.agent.HousingAdvisorSpecialistAgent;
import com.atguigu.lease.web.app.assistant.service.agent.LlmSupervisorAgent;
import com.atguigu.lease.web.app.assistant.service.agent.OrderServiceSpecialistAgent;
import com.atguigu.lease.web.app.assistant.service.agent.SpecialistAgent;
import com.atguigu.lease.web.app.assistant.service.agent.SupervisorAgent;
import com.atguigu.lease.web.app.assistant.service.agent.SupervisorPlanValidator;
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
import com.fasterxml.jackson.databind.ObjectMapper;

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
                                                   AssistantRoomTools roomTools,
                                                   AssistantBrowsingHistoryTools browsingHistoryTools,
                                                   AssistantAppointmentTools appointmentTools,
                                                   AssistantLeaseOrderTools leaseOrderTools,
                                                   AssistantKnowledgeTools knowledgeTools,
                                                   SkillPromptAugmentAdvisor skillPromptAugmentAdvisor,
                                                   @Qualifier("assistantReadSkillToolCallback") ToolCallback readSkillToolCallback,
                                                   ObjectProvider<SupervisorAgent> supervisorAgentProvider) {
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (assistantProperties.isEnabled() && chatModel != null) {
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
            SupervisorAgent supervisorAgent = supervisorAgentProvider.getIfAvailable();
            return new SupervisorAgentAssistantService(
                    promptService,
                    conversationSessionService,
                    longTermMemoryService,
                    assistantProperties,
                    supervisorAgent,
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
    public ChatClient assistantPlanningChatClient(ChatModel chatModel,
                                                  SkillPromptAugmentAdvisor skillPromptAugmentAdvisor,
                                                  @Qualifier("assistantReadSkillToolCallback") ToolCallback readSkillToolCallback) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(skillPromptAugmentAdvisor)
                .defaultToolCallbacks(readSkillToolCallback)
                .build();
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public AgentChatClientFactory agentChatClientFactory(ChatModel chatModel,
                                                         SkillPromptAugmentAdvisor skillPromptAugmentAdvisor,
                                                         @Qualifier("assistantReadSkillToolCallback") ToolCallback readSkillToolCallback,
                                                         AssistantRoomTools roomTools,
                                                         AssistantBrowsingHistoryTools browsingHistoryTools,
                                                         AssistantAppointmentTools appointmentTools,
                                                         AssistantLeaseOrderTools leaseOrderTools,
                                                         AssistantKnowledgeTools knowledgeTools) {
        return new AgentChatClientFactory(
                chatModel,
                skillPromptAugmentAdvisor,
                readSkillToolCallback,
                roomTools,
                browsingHistoryTools,
                appointmentTools,
                leaseOrderTools,
                knowledgeTools
        );
    }

    @Bean
    public AssistantSkillTemplateService assistantSkillTemplateService(ResourceLoader resourceLoader) {
        return new AssistantSkillTemplateService(resourceLoader);
    }

    @Bean
    public SupervisorPlanValidator supervisorPlanValidator() {
        return new SupervisorPlanValidator();
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public SpecialistAgent housingAdvisorSpecialistAgent(AgentChatClientFactory agentChatClientFactory,
                                                         AssistantPromptService promptService,
                                                         AssistantSkillTemplateService assistantSkillTemplateService) {
        return new HousingAdvisorSpecialistAgent(agentChatClientFactory, promptService, assistantSkillTemplateService);
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public SpecialistAgent orderServiceSpecialistAgent(AgentChatClientFactory agentChatClientFactory,
                                                       AssistantPromptService promptService,
                                                       AssistantSkillTemplateService assistantSkillTemplateService) {
        return new OrderServiceSpecialistAgent(agentChatClientFactory, promptService, assistantSkillTemplateService);
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public SpecialistAgent customerSupportSpecialistAgent(AgentChatClientFactory agentChatClientFactory,
                                                          AssistantPromptService promptService,
                                                          AssistantSkillTemplateService assistantSkillTemplateService) {
        return new CustomerSupportSpecialistAgent(agentChatClientFactory, promptService, assistantSkillTemplateService);
    }

    @Bean
    @ConditionalOnBean(ChatModel.class)
    public SupervisorAgent supervisorAgent(ChatClient assistantPlanningChatClient,
                                           AssistantPromptService promptService,
                                           AssistantSkillTemplateService assistantSkillTemplateService,
                                           SupervisorPlanValidator supervisorPlanValidator,
                                           ObjectProvider<List<SpecialistAgent>> specialistAgentsProvider,
                                           ObjectMapper objectMapper) {
        return new LlmSupervisorAgent(
                assistantPlanningChatClient,
                promptService,
                assistantSkillTemplateService,
                supervisorPlanValidator,
                specialistAgentsProvider.getIfAvailable(List::of),
                objectMapper
        );
    }
}
