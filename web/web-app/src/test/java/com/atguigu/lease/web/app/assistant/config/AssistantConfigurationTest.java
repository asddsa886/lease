package com.atguigu.lease.web.app.assistant.config;

import com.atguigu.lease.web.app.assistant.service.chat.AppAssistantService;
import com.atguigu.lease.web.app.assistant.service.chat.AssistantPromptService;
import com.atguigu.lease.web.app.assistant.service.chat.DisabledAssistantService;
import com.atguigu.lease.web.app.assistant.service.chat.OfficialSkillsAssistantService;
import com.atguigu.lease.web.app.assistant.service.memory.RedisAssistantLongTermMemoryService;
import com.atguigu.lease.web.app.assistant.service.session.RedisAssistantConversationSessionService;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantAppointmentTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantBrowsingHistoryTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantKnowledgeTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantLeaseOrderTools;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantRoomTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AssistantConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AssistantConfiguration.class, AssistantSkillsConfiguration.class)
            .withBean(AssistantPromptService.class, () -> mock(AssistantPromptService.class))
            .withBean(RedisAssistantConversationSessionService.class, () -> mock(RedisAssistantConversationSessionService.class))
            .withBean(AssistantRoomTools.class, () -> mock(AssistantRoomTools.class))
            .withBean(AssistantBrowsingHistoryTools.class, () -> mock(AssistantBrowsingHistoryTools.class))
            .withBean(AssistantAppointmentTools.class, () -> mock(AssistantAppointmentTools.class))
            .withBean(AssistantKnowledgeTools.class, () -> mock(AssistantKnowledgeTools.class))
            .withBean(RedisAssistantLongTermMemoryService.class, () -> mock(RedisAssistantLongTermMemoryService.class))
            .withBean(AssistantLeaseOrderTools.class, () -> mock(AssistantLeaseOrderTools.class));

    @Test
    void shouldCreateSpringAiAssistantServiceWhenChatModelExistsAndAssistantEnabled() {
        contextRunner
                .withPropertyValues("lease.assistant.enabled=true")
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(AppAssistantService.class);
                    assertThat(context.getBean(AppAssistantService.class)).isInstanceOf(OfficialSkillsAssistantService.class);
                });
    }

    @Test
    void shouldFallbackToDisabledAssistantServiceWhenChatModelMissing() {
        contextRunner
                .withPropertyValues("lease.assistant.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(AppAssistantService.class);
                    assertThat(context.getBean(AppAssistantService.class)).isInstanceOf(DisabledAssistantService.class);
                });
    }

    @Test
    void shouldFallbackToDisabledAssistantServiceWhenAssistantDisabled() {
        contextRunner
                .withPropertyValues("lease.assistant.enabled=false")
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(AppAssistantService.class);
                    assertThat(context.getBean(AppAssistantService.class)).isInstanceOf(DisabledAssistantService.class);
                });
    }
}
