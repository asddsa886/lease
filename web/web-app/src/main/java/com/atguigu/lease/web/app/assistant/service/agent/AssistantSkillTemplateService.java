package com.atguigu.lease.web.app.assistant.service.agent;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AssistantSkillTemplateService {

    private final ResourceLoader resourceLoader;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public AssistantSkillTemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String loadSkill(String skillName) {
        return cache.computeIfAbsent(skillName, this::readSkillContent);
    }

    private String readSkillContent(String skillName) {
        Resource resource = resourceLoader.getResource("classpath:skills/" + skillName + "/SKILL.md");
        if (!resource.exists()) {
            return "";
        }
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
