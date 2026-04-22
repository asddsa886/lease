package com.atguigu.lease.web.ops.service.assistant;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
public class FilteringSkillRegistry implements SkillRegistry {

    private final SkillRegistry delegate;
    private final Set<String> allowedSkills;

    @Override
    public Optional<SkillMetadata> get(String name) {
        return contains(name) ? delegate.get(name) : Optional.empty();
    }

    @Override
    public List<SkillMetadata> listAll() {
        return delegate.listAll().stream()
                .filter(skill -> allowedSkills.contains(skill.getName()))
                .toList();
    }

    @Override
    public boolean contains(String name) {
        return allowedSkills.contains(name) && delegate.contains(name);
    }

    @Override
    public int size() {
        return listAll().size();
    }

    @Override
    public void reload() {
        delegate.reload();
    }

    @Override
    public String readSkillContent(String name) throws IOException {
        if (!contains(name)) {
            throw new IOException("Skill not found: " + name);
        }
        return delegate.readSkillContent(name);
    }

    @Override
    public String getSkillLoadInstructions() {
        return delegate.getSkillLoadInstructions();
    }

    @Override
    public String getRegistryType() {
        return delegate.getRegistryType();
    }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return delegate.getSystemPromptTemplate();
    }
}
