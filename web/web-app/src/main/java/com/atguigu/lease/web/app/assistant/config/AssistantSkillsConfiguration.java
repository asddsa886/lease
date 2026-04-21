package com.atguigu.lease.web.app.assistant.config;

import com.alibaba.cloud.ai.graph.advisors.SkillPromptAugmentAdvisor;
import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.classpath.ClasspathSkillRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AssistantSkillsConfiguration {

    @Bean
    public SkillRegistry assistantSkillRegistry() {
        return ClasspathSkillRegistry.builder()
                .classpathPath("skills")
                .autoLoad(true)
                .build();
    }

    @Bean
    public SkillPromptAugmentAdvisor assistantSkillPromptAugmentAdvisor(SkillRegistry skillRegistry) {
        return SkillPromptAugmentAdvisor.builder()
                .skillRegistry(skillRegistry)
                .lazyLoad(false)
                .build();
    }

    @Bean
    public ToolCallback assistantReadSkillToolCallback(SkillRegistry skillRegistry) {
        return ReadSkillTool.createReadSkillToolCallback(skillRegistry, ReadSkillTool.DESCRIPTION);
    }
}
