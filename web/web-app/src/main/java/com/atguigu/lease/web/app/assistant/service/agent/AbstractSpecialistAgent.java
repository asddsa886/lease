package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.chat.AssistantPromptService;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolContextSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractSpecialistAgent implements SpecialistAgent {

    private final AgentChatClientFactory chatClientFactory;
    private final AssistantPromptService promptService;
    private final AssistantSkillTemplateService skillTemplateService;

    protected AbstractSpecialistAgent(AgentChatClientFactory chatClientFactory,
                                      AssistantPromptService promptService,
                                      AssistantSkillTemplateService skillTemplateService) {
        this.chatClientFactory = chatClientFactory;
        this.promptService = promptService;
        this.skillTemplateService = skillTemplateService;
    }

    @Override
    public SpecialistAgentResult execute(SpecialistAgentRequest request) {
        String instructions = buildInstructions(request);
        List<Message> messages = promptService.buildPromptMessages(
                request.currentUser(),
                request.history(),
                request.userMessage(),
                instructions
        );
        String reply = chatClient().prompt()
                .messages(messages)
                .toolContext(buildToolContext(request))
                .call()
                .content();
        return new SpecialistAgentResult(type(), reply, defaultNextActions());
    }

    protected abstract String skillName();

    protected abstract String roleInstructions();

    protected abstract List<AssistantNextAction> defaultNextActions();

    protected ChatClient chatClient() {
        return chatClientFactory.getClient(type());
    }

    protected String buildInstructions(SpecialistAgentRequest request) {
        StringBuilder builder = new StringBuilder();
        if (request.longTermMemoryPrompt() != null && !request.longTermMemoryPrompt().isBlank()) {
            builder.append(request.longTermMemoryPrompt().trim()).append("\n\n");
        }
        if (request.goal() != null && !request.goal().isBlank()) {
            builder.append("当前子任务目标：\n").append(request.goal().trim()).append("\n\n");
        }
        if (request.sharedContext() != null && !request.sharedContext().isBlank()) {
            builder.append("上游专员共享上下文：\n").append(request.sharedContext().trim()).append("\n\n");
        }
        String skillContent = skillTemplateService.loadSkill(skillName());
        if (!skillContent.isBlank()) {
            builder.append("当前角色技能手册：\n").append(skillContent.trim()).append("\n\n");
        }
        builder.append(roleInstructions());
        return builder.toString();
    }

    private Map<String, Object> buildToolContext(SpecialistAgentRequest request) {
        Map<String, Object> context = new HashMap<>();
        context.put(AssistantToolContextSupport.CURRENT_USER_ID, request.currentUser().getId());
        context.put(AssistantToolContextSupport.CONVERSATION_ID, request.conversationId());
        context.put(AssistantToolContextSupport.TOOL_EVENT_EMITTER, request.toolEventEmitter());
        return context;
    }
}
