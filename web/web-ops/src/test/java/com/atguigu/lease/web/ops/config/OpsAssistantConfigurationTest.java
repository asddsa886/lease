package com.atguigu.lease.web.ops.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook;
import com.atguigu.lease.web.ops.service.assistant.MultiAgentOpsAssistantService;
import com.atguigu.lease.web.ops.service.assistant.OpsAssistantService;
import com.atguigu.lease.web.ops.service.log.OpsLogScanService;
import com.atguigu.lease.web.ops.service.session.OpsAssistantSessionService;
import com.atguigu.lease.web.ops.service.tool.OpsLogAnalysisTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpsAssistantConfigurationTest {

    private final ApplicationContextRunner enabledContextRunner = new ApplicationContextRunner()
            .withPropertyValues("lease.ops.assistant.enabled=true")
            .withUserConfiguration(OpsAssistantConfiguration.class, AssistantSupportConfiguration.class);

    private final ApplicationContextRunner missingChatModelContextRunner = new ApplicationContextRunner()
            .withPropertyValues("lease.ops.assistant.enabled=true")
            .withUserConfiguration(OpsAssistantConfiguration.class, AssistantSupportWithoutChatModelConfiguration.class);

    @Test
    void shouldCreateMultiAgentAssistantServiceWhenChatModelAvailable() {
        enabledContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpsAssistantService.class);
            assertThat(context.getBean(OpsAssistantService.class)).isInstanceOf(MultiAgentOpsAssistantService.class);
            assertThat(context).hasBean("opsSupervisorAgent");
        });
    }

    @Test
    void shouldAttachSupervisorSkillHookToMainAgent() {
        enabledContextRunner.run(context -> {
            SupervisorAgent supervisorAgent = context.getBean("opsSupervisorAgent", SupervisorAgent.class);
            ReactAgent mainAgent = supervisorAgent.getMainAgent();

            @SuppressWarnings("unchecked")
            List<Object> hooks = (List<Object>) ReflectionTestUtils.getField(mainAgent, "hooks");

            assertThat(hooks)
                    .isNotNull()
                    .anyMatch(SkillsAgentHook.class::isInstance);
        });
    }

    @Test
    void shouldFailContextWhenAssistantEnabledAndChatModelMissing() {
        missingChatModelContextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining("ChatModel");
        });
    }

    @Test
    void shouldNotRegisterAssistantBeansWhenAssistantIsDisabled() {
        enabledContextRunner.withPropertyValues("lease.ops.assistant.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OpsAssistantService.class);
                    assertThat(context).doesNotHaveBean("opsSupervisorAgent");
                    assertThat(context).doesNotHaveBean("opsAppAgent");
                    assertThat(context).doesNotHaveBean("opsInfraAgent");
                    assertThat(context).doesNotHaveBean("opsPerformanceAgent");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class AssistantSupportConfiguration {

        @Bean
        ChatModel chatModel() {
            return mock(ChatModel.class);
        }

        @Bean
        OpsLogScanService opsLogScanService() {
            return mock(OpsLogScanService.class);
        }

        @Bean
        OpsAssistantSessionService opsAssistantSessionService() {
            return mock(OpsAssistantSessionService.class);
        }

        @Bean
        OpsLogAnalysisTools opsLogAnalysisTools(OpsLogScanService logScanService) {
            return new OpsLogAnalysisTools(logScanService);
        }

        @Bean("opsAppSkillRegistry")
        SkillRegistry opsAppSkillRegistry() {
            return mock(SkillRegistry.class);
        }

        @Bean("opsInfraSkillRegistry")
        SkillRegistry opsInfraSkillRegistry() {
            return mock(SkillRegistry.class);
        }

        @Bean("opsPerformanceSkillRegistry")
        SkillRegistry opsPerformanceSkillRegistry() {
            return mock(SkillRegistry.class);
        }

        @Bean("opsSupervisorSkillRegistry")
        SkillRegistry opsSupervisorSkillRegistry() {
            return mock(SkillRegistry.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AssistantSupportWithoutChatModelConfiguration {

        @Bean
        OpsLogScanService opsLogScanService() {
            return mock(OpsLogScanService.class);
        }

        @Bean
        OpsAssistantSessionService opsAssistantSessionService() {
            return mock(OpsAssistantSessionService.class);
        }

        @Bean
        OpsLogAnalysisTools opsLogAnalysisTools(OpsLogScanService logScanService) {
            return new OpsLogAnalysisTools(logScanService);
        }

        @Bean("opsAppSkillRegistry")
        SkillRegistry opsAppSkillRegistry() {
            return mock(SkillRegistry.class);
        }

        @Bean("opsInfraSkillRegistry")
        SkillRegistry opsInfraSkillRegistry() {
            return mock(SkillRegistry.class);
        }

        @Bean("opsPerformanceSkillRegistry")
        SkillRegistry opsPerformanceSkillRegistry() {
            return mock(SkillRegistry.class);
        }

        @Bean("opsSupervisorSkillRegistry")
        SkillRegistry opsSupervisorSkillRegistry() {
            return mock(SkillRegistry.class);
        }
    }
}
