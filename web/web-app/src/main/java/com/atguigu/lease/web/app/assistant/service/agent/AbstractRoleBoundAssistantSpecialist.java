package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.chat.AssistantPromptService;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationMessage;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolContextSupport;
import com.atguigu.lease.web.app.assistant.service.tool.AssistantToolEventEmitter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractRoleBoundAssistantSpecialist implements AssistantSpecialist {

    private final ChatClient chatClient;
    private final AssistantPromptService promptService;
    private final AssistantSkillTemplateService skillTemplateService;

    protected AbstractRoleBoundAssistantSpecialist(ChatClient chatClient,
                                                   AssistantPromptService promptService,
                                                   AssistantSkillTemplateService skillTemplateService) {
        this.chatClient = chatClient;
        this.promptService = promptService;
        this.skillTemplateService = skillTemplateService;
    }

    @Override
    public AssistantSpecialistResult handle(AssistantSpecialistRequest request) {
        String instructions = buildInstructions(request.extraInstructions());
        List<Message> messages = promptService.buildPromptMessages(
                request.currentUser(),
                request.history(),
                request.userMessage(),
                instructions
        );
        String reply = chatClient.prompt()
                .messages(messages)
                .toolContext(buildToolContext(request))
                .call()
                .content();
        return new AssistantSpecialistResult(type(), reply, defaultNextActions());
    }

    protected abstract String skillName();

    protected abstract String roleInstructions();

    protected abstract List<AssistantNextAction> defaultNextActions();

    protected String buildInstructions(String extraInstructions) {
        StringBuilder builder = new StringBuilder();
        if (extraInstructions != null && !extraInstructions.isBlank()) {
            builder.append(extraInstructions.trim()).append("\n\n");
        }
        String skillContent = skillTemplateService.loadSkill(skillName());
        if (!skillContent.isBlank()) {
            builder.append("当前角色技能手册：\n").append(skillContent.trim()).append("\n\n");
        }
        builder.append(roleInstructions());
        return builder.toString();
    }

    private Map<String, Object> buildToolContext(AssistantSpecialistRequest request) {
        Map<String, Object> context = new HashMap<>();
        context.put(AssistantToolContextSupport.CURRENT_USER_ID, request.currentUser().getId());
        context.put(AssistantToolContextSupport.CONVERSATION_ID, request.conversationId());
        context.put(AssistantToolContextSupport.TOOL_EVENT_EMITTER, request.toolEventEmitter());
        return context;
    }
}
