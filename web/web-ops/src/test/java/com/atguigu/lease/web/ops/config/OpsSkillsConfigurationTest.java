package com.atguigu.lease.web.ops.config;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OpsSkillsConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OpsSkillsConfiguration.class);

    @Test
    void shouldLoadAllOpsSkills() {
        contextRunner.run(context -> {
            SkillRegistry rootRegistry = context.getBean("opsRootSkillRegistry", SkillRegistry.class);
            SkillRegistry supervisorRegistry = context.getBean("opsSupervisorSkillRegistry", SkillRegistry.class);
            SkillRegistry appRegistry = context.getBean("opsAppSkillRegistry", SkillRegistry.class);
            SkillRegistry infraRegistry = context.getBean("opsInfraSkillRegistry", SkillRegistry.class);
            SkillRegistry performanceRegistry = context.getBean("opsPerformanceSkillRegistry", SkillRegistry.class);

            assertThat(rootRegistry.size()).isEqualTo(4);
            assertThat(rootRegistry.contains("ops-triage-policy")).isTrue();
            assertThat(rootRegistry.contains("app-crash-analysis")).isTrue();
            assertThat(rootRegistry.contains("infra-dependency-analysis")).isTrue();
            assertThat(rootRegistry.contains("performance-db-analysis")).isTrue();

            assertThat(supervisorRegistry.size()).isEqualTo(1);
            assertThat(supervisorRegistry.contains("ops-triage-policy")).isTrue();
            assertThat(appRegistry.contains("app-crash-analysis")).isTrue();
            assertThat(infraRegistry.contains("infra-dependency-analysis")).isTrue();
            assertThat(performanceRegistry.contains("performance-db-analysis")).isTrue();
            assertThat(rootRegistry.readSkillContent("ops-triage-policy")).contains("runLogScan");
        });
    }
}
