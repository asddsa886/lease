package com.atguigu.lease.web.app.assistant.config;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantSkillsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AssistantSkillsConfiguration.class);

    @Test
    void shouldLoadClasspathSkillsFromRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SkillRegistry.class);
            assertThat(context).hasSingleBean(SkillPromptAugmentAdvisor.class);
            SkillRegistry skillRegistry = context.getBean(SkillRegistry.class);
            assertThat(skillRegistry.size()).isEqualTo(8);
            assertThat(skillRegistry.contains("house-search")).isTrue();
            assertThat(skillRegistry.contains("appointment-service")).isTrue();
            assertThat(skillRegistry.contains("lease-order")).isTrue();
            assertThat(skillRegistry.contains("knowledge-qa")).isTrue();
            assertThat(skillRegistry.contains("supervisor-routing")).isTrue();
            assertThat(skillRegistry.contains("housing-advisor")).isTrue();
            assertThat(skillRegistry.contains("order-service")).isTrue();
            assertThat(skillRegistry.contains("customer-support")).isTrue();
            assertThat(skillRegistry.readSkillContent("house-search")).contains("searchRooms");
        });
    }

    @Test
    void shouldExposeReadSkillToolCallback() {
        contextRunner.run(context -> {
            ToolCallback toolCallback = context.getBean("assistantReadSkillToolCallback", ToolCallback.class);
            assertThat(toolCallback.getToolDefinition().name()).isEqualTo("read_skill");
            assertThat(toolCallback.call("{\"skill_name\":\"lease-order\"}")).contains("createLeaseOrder");
            assertThat(toolCallback.call("{\"skill_name\":\"missing-skill\"}")).contains("Skill not found: missing-skill");
        });
    }
}
