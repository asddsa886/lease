package com.atguigu.lease.web.ops.config;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import com.atguigu.lease.web.ops.service.assistant.FilteringSkillRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class OpsSkillsConfiguration {

    @Bean("opsRootSkillRegistry")
    public SkillRegistry opsRootSkillRegistry() {
        return ClasspathSkillRegistry.builder()
                .classpathPath("skills")
                .autoLoad(true)
                .build();
    }

    @Bean("opsSupervisorSkillRegistry")
    public SkillRegistry opsSupervisorSkillRegistry(SkillRegistry opsRootSkillRegistry) {
        return new FilteringSkillRegistry(opsRootSkillRegistry, Set.of("ops-triage-policy"));
    }

    @Bean("opsAppSkillRegistry")
    public SkillRegistry opsAppSkillRegistry(SkillRegistry opsRootSkillRegistry) {
        return new FilteringSkillRegistry(opsRootSkillRegistry, Set.of("app-crash-analysis"));
    }

    @Bean("opsInfraSkillRegistry")
    public SkillRegistry opsInfraSkillRegistry(SkillRegistry opsRootSkillRegistry) {
        return new FilteringSkillRegistry(opsRootSkillRegistry, Set.of("infra-dependency-analysis"));
    }

    @Bean("opsPerformanceSkillRegistry")
    public SkillRegistry opsPerformanceSkillRegistry(SkillRegistry opsRootSkillRegistry) {
        return new FilteringSkillRegistry(opsRootSkillRegistry, Set.of("performance-db-analysis"));
    }
}
