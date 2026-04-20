package com.atguigu.lease.web.app.assistant.service.agent;

import com.atguigu.lease.common.login.LoginUser;
import com.atguigu.lease.web.app.assistant.dto.AssistantNextAction;
import com.atguigu.lease.web.app.assistant.service.chat.AssistantPromptService;
import com.atguigu.lease.web.app.assistant.service.session.AssistantConversationMessage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public abstract class AbstractAssistantAgent {

    private final ChatClient chatClient;

    protected AbstractAssistantAgent(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    public abstract AssistantAgentRoute route();

    public abstract String specialistInstructions(AssistantSupervisorDecision decision);

    public abstract List<AssistantNextAction> nextActions();

    protected Object[] tools() {
        return new Object[0];
    }

    public String chat(LoginUser currentUser,
                       List<AssistantConversationMessage> history,
                       String userMessage,
                       AssistantSupervisorDecision decision,
                       AssistantPromptService promptService,
                       Map<String, Object> toolContext) {
        List<Message> messages = promptService.buildPromptMessages(
                currentUser,
                history,
                userMessage,
                specialistInstructions(decision)
        );
        if (tools().length > 0) {
            return chatClient.prompt()
                    .messages(messages)
                    .tools(tools())
                    .toolContext(toolContext)
                    .call()
                    .content();
        }
        return chatClient.prompt()
                .messages(messages)
                .call()
                .content();
    }

    public Flux<String> stream(LoginUser currentUser,
                               List<AssistantConversationMessage> history,
                               String userMessage,
                               AssistantSupervisorDecision decision,
                               AssistantPromptService promptService,
                               Map<String, Object> toolContext) {
        List<Message> messages = promptService.buildPromptMessages(
                currentUser,
                history,
                userMessage,
                specialistInstructions(decision)
        );
        if (tools().length > 0) {
            return chatClient.prompt()
                    .messages(messages)
                    .tools(tools())
                    .toolContext(toolContext)
                    .stream()
                    .content();
        }
        return chatClient.prompt()
                .messages(messages)
                .stream()
                .content();
    }
}
