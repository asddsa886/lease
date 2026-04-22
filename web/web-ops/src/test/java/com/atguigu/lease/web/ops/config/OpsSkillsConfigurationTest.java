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
            String triageSkill = rootRegistry.readSkillContent("ops-triage-policy");
            String appSkill = rootRegistry.readSkillContent("app-crash-analysis");
            String infraSkill = rootRegistry.readSkillContent("infra-dependency-analysis");
            String performanceSkill = rootRegistry.readSkillContent("performance-db-analysis");

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
            assertThat(triageSkill)
                    .contains("runLogScan")
                    .contains("searchHistoryScans")
                    .contains("只输出 JSON 数组")
                    .contains("FINISH");
            assertThat(appSkill)
                    .contains("getLatestScanReport")
                    .contains("getIssueDetail")
                    .contains("证据不足")
                    .contains("不要把关键词联想当成已经定位到根因");
            assertThat(infraSkill)
                    .contains("listDependencyFailures")
                    .contains("searchLogEvidence")
                    .contains("不要仅凭一条 timeout")
                    .contains("认证失败");
            assertThat(performanceSkill)
                    .contains("listSlowSqlFindings")
                    .contains("HIGH_REQUEST_LATENCY")
                    .contains("不要把“接口慢”直接等同于“SQL 慢”")
                    .contains("连接池");
        });
    }
}
